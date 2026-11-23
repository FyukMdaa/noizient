#include <jni.h>
#include <android/asset_manager_jni.h>
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <optional>
#if defined(__SSE__)
#include <xmmintrin.h>
#endif
#include "overlay.h"

using namespace noizient;
namespace {
constexpr int controlCount=18;
using Controls=std::array<double,controlCount>;
std::vector<uint8_t> readAsset(AAssetManager* manager,const char* name) {
    std::unique_ptr<AAsset,decltype(&AAsset_close)> asset(AAssetManager_open(manager,name,AASSET_MODE_BUFFER),AAsset_close);
    if(!asset) return {};
    const auto* data=static_cast<const uint8_t*>(AAsset_getBuffer(asset.get()));
    return data ? std::vector<uint8_t>(data,data+AAsset_getLength(asset.get())) : std::vector<uint8_t>{};
}
struct Assets {
    Profile focus,sleep;
    int filterRate=0;
    std::array<BandCoefficients,10> filters{};
    explicit Assets(AAssetManager* manager)
        : focus(false,readAsset(manager,"focus.bin")),sleep(true,readAsset(manager,"sleep.bin")) {}
};
class EventQueue {
    static constexpr uint32_t capacity=240;
    std::array<Event,capacity> events{};
    std::atomic<uint32_t> write{0},read{0};
public:
    void push(Event e) {
        uint32_t index=write.load(std::memory_order_relaxed);
        if(index-read.load(std::memory_order_acquire)==capacity) return;
        events[index%capacity]=e;
        write.store(index+1,std::memory_order_release);
    }
    bool pop(Event& e) {
        uint32_t index=read.load(std::memory_order_relaxed);
        if(index==write.load(std::memory_order_acquire)) return false;
        e=events[index%capacity];
        read.store(index+1,std::memory_order_release);
        return true;
    }
};
void flushDenormals() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= 1ull<<24;
    asm volatile("msr fpcr, %0" :: "r"(fpcr));
#elif defined(__SSE__)
    _MM_SET_FLUSH_ZERO_MODE(_MM_FLUSH_ZERO_ON);
#endif
}
class StreamErrors : public oboe::AudioStreamErrorCallback {
public:
    std::atomic<bool> failed{false};
    void onErrorAfterClose(oboe::AudioStream*, oboe::Result) override { failed.store(true); }
};
class Audio : public oboe::AudioStreamDataCallback {
    std::shared_ptr<oboe::AudioStream> stream;
    std::shared_ptr<StreamErrors> errors = std::make_shared<StreamErrors>();
    std::optional<FocusRenderer> focus;
    std::optional<SleepRenderer> sleep;
    std::optional<AmbientOverlay> overlay;
    std::array<std::atomic<int>,controlCount> controls{};
    EventQueue events;
    uint64_t count=0;
    std::atomic<uint32_t> milliseconds{0};
    std::atomic<bool> running{true},timerDone{false};
    std::atomic<int> timerChange{-1};
    uint64_t deadline=0;
    double timerRecovery=1;
    double master=0,startSlope=0,stopSlope=0;
    int rate=0;
public:
    ~Audio() { if(stream) stream->close(); }
    void configure(const Controls& c) {
        for(int i=0;i<controlCount;++i) {
            double value=std::isfinite(c[i]) ? c[i] : 0;
            controls[i].store(std::lround(std::clamp(value,0.,100.)*1000),std::memory_order_relaxed);
        }
    }
    bool open(Assets& assets,bool isSleep,uint32_t seed,int duration,const Controls& c,int timer) {
        const auto& profile=isSleep ? assets.sleep : assets.focus;
        if(profile.levels.empty()) return false;
        oboe::AudioStreamBuilder builder;
        builder.setAudioApi(oboe::AudioApi::AAudio)
            ->setDirection(oboe::Direction::Output)->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(2)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)->setDataCallback(this)->setErrorCallback(errors);
        if(builder.openStream(stream)!=oboe::Result::OK) return false;
        if(stream->getChannelCount()!=2 || stream->getFormat()!=oboe::AudioFormat::Float) return false;
        rate=stream->getSampleRate();
        if(rate<=0) return false;
        startSlope=-std::expm1(-1/(rate*.45)); stopSlope=-std::expm1(-1/(rate*.045));
        configure(c);
        std::array<double,3> mix{c[0],c[1],c[2]};
        Layers layers{};
        std::copy_n(c.begin()+3,12,layers.begin());
        if(isSleep) {
            int internal=std::min(rate,24000);
            if(assets.filterRate!=internal) {
                assets.filters=calibrate(profile,internal);
                assets.filterRate=internal;
            }
            sleep.emplace(profile,rate,seed,mix,std::max(duration,120),assets.filters);
        } else focus.emplace(profile,rate,seed,mix);
        overlay.emplace(rate,seed,layers);
        timerChange.store(timer);
        return stream->requestStart()==oboe::Result::OK;
    }
    void pause(bool paused) { running.store(!paused); }
    void timer(int seconds) { timerChange.store(seconds); }
    int status() const { return errors->failed.load() ? -1 : timerDone.load() ? -2 : int(milliseconds.load()); }
    bool event(Event& e) { return events.pop(e); }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,void* output,int32_t frames) override {
        flushDenormals();
        Controls c{};
        for(int i=0;i<controlCount;++i) c[i]=controls[i].load(std::memory_order_relaxed)*.001;
        const std::array<double,3> mix{c[0],c[1],c[2]};
        Layers layers{};
        std::copy_n(c.begin()+3,12,layers.begin());
        int timer=timerChange.exchange(-1);
        if(timer>=0) {
            timerRecovery=deadline ? unit((double(deadline)-count)/(20.*rate)) : timerRecovery;
            deadline=timer ? count+uint64_t(timer)*rate : 0;
        }
        const bool playing=running.load(std::memory_order_relaxed);
        auto* out=static_cast<float*>(output);
        for(int i=0;i<frames;++i) {
            Stereo sample;
            if(playing || master>.0005) {
                sample=focus ? focus->sample(mix) : sleep->sample(mix);
                overlay->add(sample,layers,int(c[16]),c[17]>0,[this](Event e){events.push(e);});
                ++count;
            }
            timerRecovery=std::min(1.,timerRecovery+1/(.4*rate));
            const double fade=deadline ? std::min(timerRecovery,unit((double(deadline)-count)/(20.*rate))) : timerRecovery;
            master=lerp(master,playing ? c[15]*.01 : 0,playing ? startSlope : stopSlope);
            const auto protect=[&](double x) {
                x*=master*fade*.72;
                if(!std::isfinite(x)) return 0.;
                double absolute=std::abs(x);
                return absolute<=.85 ? x : std::copysign(.85+.14*std::tanh((absolute-.85)/.14),x);
            };
            out[2*i]=protect(sample.l); out[2*i+1]=protect(sample.r);
            if(deadline && count>=deadline) timerDone.store(true,std::memory_order_relaxed);
        }
        milliseconds.store(count*1000/rate,std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }
};
Controls getControls(JNIEnv* env,jdoubleArray array) {
    Controls result{};
    env->GetDoubleArrayRegion(array,0,controlCount,result.data());
    return result;
}
}

extern "C" {
JNIEXPORT jlong JNICALL Java_dev_noizient_Native_load(JNIEnv* env,jobject,jobject manager) {
    static Assets assets(AAssetManager_fromJava(env,manager));
    return reinterpret_cast<jlong>(&assets);
}
JNIEXPORT jlong JNICALL Java_dev_noizient_Native_open(JNIEnv* env,jobject,jlong assets,jboolean sleep,
        jlong seed,jint intro,jdoubleArray values,jint timer) {
    auto audio=std::make_unique<Audio>();
    if(!audio->open(*reinterpret_cast<Assets*>(assets),sleep,seed,intro,getControls(env,values),timer)) return 0;
    return reinterpret_cast<jlong>(audio.release());
}
JNIEXPORT void JNICALL Java_dev_noizient_Native_close(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<Audio*>(handle);
}
JNIEXPORT void JNICALL Java_dev_noizient_Native_configure(JNIEnv* env,jobject,jlong handle,jdoubleArray values) {
    reinterpret_cast<Audio*>(handle)->configure(getControls(env,values));
}
JNIEXPORT void JNICALL Java_dev_noizient_Native_pause(JNIEnv*,jobject,jlong handle,jboolean paused) {
    reinterpret_cast<Audio*>(handle)->pause(paused);
}
JNIEXPORT void JNICALL Java_dev_noizient_Native_timer(JNIEnv*,jobject,jlong handle,jint seconds) {
    reinterpret_cast<Audio*>(handle)->timer(seconds);
}
JNIEXPORT jint JNICALL Java_dev_noizient_Native_status(JNIEnv*,jobject,jlong handle) {
    return reinterpret_cast<Audio*>(handle)->status();
}
JNIEXPORT jint JNICALL Java_dev_noizient_Native_events(JNIEnv* env,jobject,jlong handle,jdoubleArray output) {
    auto& audio=*reinterpret_cast<Audio*>(handle);
    int count=0;
    std::array<double,240*4> result;
    Event event{};
    while(count<240 && audio.event(event)) {
        result[count*4]=event.time; result[count*4+1]=event.logic;
        result[count*4+2]=event.value; result[count*4+3]=event.detail;
        ++count;
    }
    if(count) env->SetDoubleArrayRegion(output,0,count*4,result.data());
    return count;
}
}
