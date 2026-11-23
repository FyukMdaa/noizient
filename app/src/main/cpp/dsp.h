#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace noizient {
constexpr double tau = 6.2831853071795864769;
struct Stereo { double l = 0, r = 0; };
inline double lerp(double a, double b, double f) { return a + (b - a) * f; }
inline double unit(double x) { return std::clamp(x, 0., 1.); }
inline double smooth(double x) { x = unit(x); return x*x*(3-2*x); }
inline double lp(double hz, double rate) { return -std::expm1(-tau * hz / rate); }
inline double bell(double x) { const double s = std::sin(tau*.5*x); return s*s; }
struct Random {
    uint32_t state;
    double next() { state = state * 1664525u + 1013904223u; return state / 4294967296.; }
    double white() { return 2*next()-1; }
};
struct Profile {
    bool sleep;
    int frames, voices;
    std::array<double,48> hz{}, phase{};
    std::array<double,11> edges{};
    std::vector<uint8_t> levels, extra;
    Profile(bool isSleep, const std::vector<uint8_t>& data)
        : sleep(isSleep), frames(sleep ? 5596 : 913), voices(sleep ? 48 : 47) {
        const size_t metadata = (2*voices + (sleep ? 11 : 0))*8;
        const size_t levelSize = voices*frames*3;
        if (data.size() != metadata + levelSize + frames*(sleep ? 20 : 80)) return;
        std::memcpy(hz.data(), data.data(), voices*8);
        std::memcpy(phase.data(), data.data()+voices*8, voices*8);
        if (sleep) std::memcpy(edges.data(), data.data()+voices*16, 88);
        levels.assign(data.begin()+metadata, data.begin()+metadata+levelSize);
        extra.assign(data.begin()+metadata+levelSize, data.end());
    }
};
struct Position { double ref, second, blend; };
inline Position focusPosition(double elapsed) {
    double ref = elapsed + 22;
    if (ref >= 911) ref = 45 + std::fmod(ref-911,866);
    double blend = unit((ref-901)/10);
    return {ref,35+blend*10,blend};
}
inline Position sleepPosition(double elapsed, double duration) {
    double ref = elapsed <= 12 ? 38+elapsed : elapsed < duration
        ? lerp(50,338,(elapsed-12)/(duration-12)) : 338+elapsed-duration;
    if (ref >= 2832) ref = 2698 + std::fmod(ref-2832,134);
    double blend = unit((ref-2824)/8);
    return {ref,2690+blend*8,blend};
}
struct Ramp {
    double value = 0, delta = 0;
    void to(double target, int frames) { delta = (target-value)/frames; }
    double take() { double old = value; value += delta; return old; }
};
struct Voice { double angle = 0, shift = 0; Ramp l,r,inc; };
class Tones {
protected:
    const Profile& p;
    int rate, step, remaining = 0;
    uint64_t count = 0;
    Random random;
    std::array<double,3> mix;
    std::array<Voice,48> voices{};
    std::array<float,8193> sine{};
    std::array<float,256> amp{};
    int size;
    Tones(const Profile& profile, int hz, int block, uint32_t seed, std::array<double,3> initial)
        : p(profile), rate(hz), step(block), random{seed}, mix(initial), size(p.sleep ? 4096 : 8192) {
        for (int i=0;i<=size;++i) sine[i]=std::sin(tau*i/size);
        for (int i=1;i<256;++i) amp[i]=std::pow(10,(p.sleep ? .5*i-90 : i/3.-80)/20);
        for (int i=0;i<p.voices;++i) {
            auto& v=voices[i];
            v.angle=random.next()*size;
            v.shift=std::fmod(size-p.phase[i]*size/tau,size);
            v.inc.value=p.hz[i]*size/rate;
        }
    }
    double read(const std::vector<uint8_t>& data, double position, int offset, int stride, bool amplitude) const {
        const int frame=static_cast<int>(position), i=offset+frame*stride;
        const auto a=data[i], b=data[i+stride];
        return lerp(amplitude ? amp[a] : a, amplitude ? amp[b] : b, position-frame);
    }
    double sinAt(double angle) const {
        if (angle >= size) angle -= size;
        if (angle >= size) angle = 0;
        int i=static_cast<int>(angle);
        return lerp(sine[i],sine[i+1],angle-i);
    }
    Stereo tones() {
        Stereo out;
        for (int i=0;i<p.voices;++i) {
            auto& v=voices[i];
            out.l+=v.l.take()*sinAt(v.angle);
            out.r+=v.r.take()*sinAt(v.angle+v.shift);
            v.angle+=v.inc.take();
            if(v.angle>=size) v.angle-=size;
        }
        return out;
    }
    void follow(const std::array<double,3>& target,double speed) {
        for(int i=0;i<3;++i) mix[i]=lerp(mix[i],target[i],speed);
    }
};
class FocusRenderer : Tones {
    Stereo noise;
    double air=0, pole;
    std::array<double,3> state(int i,double t) const {
        int offset=i*p.frames*3;
        double hz=p.hz[i];
        int slot=hz==103.41 ? 0 : hz==200.65 ? 2 : -1;
        std::array<double,3> s{};
        s[2]=slot>=0 ? hz : hz+(read(p.levels,t,offset,3,false)-128)/8;
        for(int ch=0;ch<2;++ch) {
            if(slot<0) s[ch]=read(p.levels,t,offset+ch+1,3,true);
            else {
                const auto envelope=[&](double ref){return read(p.extra,ref*20,(slot+ch)*p.frames*20,1,true);};
                const double middle=envelope(t);
                const double average=(envelope(t-.4)+envelope(t-.2)+middle+envelope(t+.2)+envelope(t+.4))/5;
                s[ch]=std::max(0.,lerp(average,middle,std::min(1.35,mix[2]/39)));
            }
            s[ch]*=mix[0]/68;
        }
        return s;
    }
    void update(const std::array<double,3>& target) {
        follow(target,.065);
        const auto pos=focusPosition(double(count)/rate);
        for(int i=0;i<p.voices;++i) {
            auto s=state(i,pos.ref);
            if(pos.blend>0) {
                auto other=state(i,pos.second);
                for(int j=0;j<3;++j) s[j]=lerp(s[j],other[j],pos.blend);
            }
            auto& v=voices[i];
            v.l.to(s[0],step); v.r.to(s[1],step); v.inc.to(s[2]*size/rate,step);
        }
        air=mix[1]/43*.022;
        remaining=step;
    }
public:
    FocusRenderer(const Profile& profile,int fs,uint32_t seed,std::array<double,3> initial)
        : Tones(profile,fs,std::max(32,fs/100),seed,initial),pole(lp(380,fs)) {}
    Stereo sample(const std::array<double,3>& target) {
        if(!remaining) update(target);
        auto out=tones();
        noise.l=lerp(noise.l,random.white(),pole);
        noise.r=lerp(noise.r,random.white(),pole);
        out.l+=air*noise.l; out.r+=air*noise.r;
        --remaining; ++count;
        return out;
    }
};
struct BandCoefficients {
    double b=0,a1=0,a2=0,gain=0;
    double filter(double x, std::array<double,8>& state) const {
        for(int k=0;k<8;k+=2) {
            double y=b*x+state[k];
            state[k]=-a1*y+state[k+1];
            state[k+1]=-b*x-a2*y;
            x=y;
        }
        return x;
    }
};
inline std::array<BandCoefficients,10> calibrate(const Profile& p,int fs) {
    std::array<BandCoefficients,10> bands{};
    for(int i=0;i<10;++i) {
        double lo=p.edges[i], hi=p.edges[i+1], center=lo ? std::sqrt(lo*hi) : 13;
        double w=tau*std::min(center,fs*.43)/fs;
        double alpha=std::sin(w)/(2*(lo ? center/(hi-lo) : .85));
        auto& b=bands[i];
        b={alpha/(1+alpha),-2*std::cos(w)/(1+alpha),(1-alpha)/(1+alpha),0};
        std::array<double,8> memory{};
        double power=0;
        for(int j=0;j<std::ceil(fs*8/center);++j) {
            double x=b.filter(j==0 ? 1 : 0,memory);
            power+=x*x;
        }
        b.gain=std::sqrt(3/power);
    }
    return bands;
}
class SleepRenderer : Tones {
    struct Band { Ramp l,r; std::array<double,8> left{},right{}; };
    std::array<Band,10> bands{};
    std::array<BandCoefficients,10> coefficients;
    Stereo left,next,low;
    double ratio, frac=1, duration, highPass;
    void update(const std::array<double,3>& target) {
        follow(target,.13);
        const auto pos=sleepPosition(double(count)/rate,duration);
        const auto frame=[&](double ref){return std::clamp((ref-38)/.5,0.,p.frames-1.001);};
        const auto value=[&](const auto& data,int stride,int offset,bool amplitude) {
            const double a=read(data,frame(pos.ref),offset,stride,amplitude);
            return pos.blend ? lerp(a,read(data,frame(pos.second),offset,stride,amplitude),pos.blend) : a;
        };
        const double bed=mix[0]/58, air=mix[1]/44, chime=mix[2]/57;
        const double fg=unit((338-pos.ref)/42);
        for(int i=0;i<p.voices;++i) {
            auto& v=voices[i];
            int stride=p.voices*3, offset=i*3;
            double g=p.hz[i]<245 ? bed : lerp(air,chime,fg);
            v.l.to(g*value(p.levels,stride,offset+1,true),step);
            v.r.to(g*value(p.levels,stride,offset+2,true),step);
            v.inc.to((p.hz[i]+(value(p.levels,stride,offset,false)-128)/16)*size/rate,step);
        }
        for(int i=0;i<10;++i) {
            const double g=i<5 ? bed : air;
            bands[i].l.to(g*value(p.extra,20,i*2,true),step);
            bands[i].r.to(g*value(p.extra,20,i*2+1,true),step);
        }
        remaining=step;
    }
    Stereo internal(const std::array<double,3>& target) {
        if(!remaining) update(target);
        auto out=tones();
        for(int i=0;i<10;++i) {
            auto& band=bands[i];
            const auto& coeff=coefficients[i];
            out.l+=band.l.take()*coeff.gain*coeff.filter(random.white(),band.left);
            out.r+=band.r.take()*coeff.gain*coeff.filter(random.white(),band.right);
        }
        --remaining; ++count;
        low.l=lerp(low.l,out.l,highPass);
        low.r=lerp(low.r,out.r,highPass);
        return {out.l-low.l,out.r-low.r};
    }
public:
    SleepRenderer(const Profile& profile,int fs,uint32_t seed,std::array<double,3> initial,
                  double seconds,const std::array<BandCoefficients,10>& filters)
        : Tones(profile,std::min(fs,24000),std::max(1,std::min(fs,24000)/32),seed,initial),
          coefficients(filters),ratio(double(rate)/fs),duration(seconds),highPass(lp(25,rate)) {}
    Stereo sample(const std::array<double,3>& target) {
        if(frac>=1) { frac-=1; left=next; next=internal(target); }
        Stereo out{lerp(left.l,next.l,frac),lerp(left.r,next.r,frac)};
        frac+=ratio;
        return out;
    }
};
}
