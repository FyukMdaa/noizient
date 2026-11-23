#pragma once
#include "dsp.h"
#include <limits>

namespace noizient {
struct Event { double time, logic, value, detail; };
using Layers = std::array<double,12>;
enum Layer { Kick,Tempo,Attack,Tight,Wave,Fire,Bird,Rustle,Activity,Width,Speed,Warmth };

class AmbientOverlay {
    struct Cloud {
        double start=-1,duration=0,next;
        Cloud(double first) : next(first) {}
        bool active(double t) const { return t>=start && t<start+duration; }
        double envelope(double t) const { return active(t) ? bell((t-start)/duration) : 0; }
    };
    int rate;
    uint64_t frames=0;
    double dt, smoothing;
    Random noise, events;
    Layers v;
    double angle, drift, rustlePhase, angleStep=0, activity=1, period=3;
    std::array<double,4> pan{};
    Cloud wave, fire, bird;
    double waveOffset=0, fireLP=0, fireHP=0;
    double kickNext=.75,kickAge=10,kickPhase=0,clickFast=0,clickSlow=0;
    double popNext=0,popAge=1,popDecay=.06,popAmp=0,popPan=0;
    double chirpNext=0,chirpAge=1,chirpDuration=.15,chirpFrequency=1500;
    double chirpGlide=0,chirpPhase=0,chirpAmp=0,chirpPan=0;
    std::array<double,2> waveLow{},waveMid{},waveHigh{},fireFast{},fireSlow{},rustleFast{},rustleSlow{};
    double aw,am,ah,apop,bpop,ar1,ar2;
    static double beatGate(double t,int pattern) {
        if(pattern==0) return 1;
        if(pattern==1) {
            double x=std::fmod(t,210.);
            return smooth((x-7)/24)*(1-smooth((x-115)/30));
        }
        double x=std::fmod(t,913.);
        return smooth((x-300)/22)*(1-smooth((x-778)/22));
    }
    void refresh(double t,const Layers& target) {
        for(size_t i=0;i<v.size();++i) v[i]=lerp(v[i],target[i],smoothing);
        period=60/std::max(6.,v[Tempo]);
        activity=.55+.0105*v[Activity];
        const double width=.0064*v[Width];
        const std::array<double,4> offsets{0,1.08,2.12,.58};
        for(size_t i=0;i<pan.size();++i) {
            double s=offsets[i];
            pan[i]=width*(.84*std::sin(angle+s)+.16*std::sin(2.17*angle+drift+s));
        }
        fireLP=lp(2400-15*v[Warmth],rate);
        fireHP=lp(210-.65*v[Warmth],rate);
        angleStep=tau/(rate*(34-.23*v[Speed]))*(1+.13*std::sin(tau*t/103+drift));
    }
    void cloudStart(Cloud& c,double t,double duration,double gap) {
        c.start=t; c.duration=duration; c.next=t+duration+gap/activity;
    }
    static double channelGain(int ch,double p) {
        return std::sqrt(1+(ch ? 1 : -1)*std::clamp(p,-.85,.85));
    }
public:
    AmbientOverlay(int fs,uint32_t seed,Layers layers)
        : rate(fs),dt(1./fs),smoothing(-std::expm1(-128./(fs*.28))),
          noise{seed^0x9e3779b9u},events{seed^0x85ebca6bu},v(layers),
          angle(0),drift(0),rustlePhase(0),wave(2.5+3*events.next()),fire(0),bird(0),
          aw(lp(180,fs)),am(lp(1250,fs)),ah(lp(5200,fs)),apop(lp(3900,fs)),
          bpop(lp(460,fs)),ar1(lp(300,fs)),ar2(lp(3600,fs)) {
        angle=tau*events.next(); drift=tau*events.next();
        fire.next=7+11*events.next(); bird.next=12+18*events.next();
        rustlePhase=tau*events.next();
    }
    template<class Sink>
    void add(Stereo& out,const Layers& target,int pattern,bool experimental,Sink emit) {
        const double t=double(frames)/rate;
        if((frames++&127)==0) refresh(t,target);
        angle+=angleStep;
        // Both pan components repeat together after 100 base revolutions.
        if(angle>=100*tau) angle-=100*tau;
        if(t>=kickNext) {
            kickNext=t+period*(experimental ? .985+.030*events.next() : 1);
            kickAge=0; kickPhase=0;
            const double level=beatGate(t,pattern)*v[Kick]/100;
            if(level>.018) emit(Event{t,0,level,period});
        }
        const double raw=noise.white();
        clickFast=lerp(clickFast,raw,apop);
        clickSlow=lerp(clickSlow,raw,bpop);
        if(v[Kick]>.0001 && kickAge<1.5) {
            const double tight=v[Tight]/100, age=kickAge;
            const double env=-std::expm1(-age/.0016)*std::exp(-age/(.155-.075*tight));
            const double hz=48+9*(1-tight)+(82+35*tight)*std::exp(-age/(.016+.014*(1-tight)));
            kickPhase+=tau*hz*dt;
            const double click=(clickFast-clickSlow)*v[Attack]/100*-std::expm1(-age/.0007)*std::exp(-age/.009);
            const double pulse=(std::sin(kickPhase)*env+click*2.1)*.0029*v[Kick]*beatGate(t,pattern);
            out.l+=pulse; out.r+=pulse;
        }
        kickAge+=dt;
        if(t>=wave.next) {
            const double duration=5.2+5.7*events.next(), gap=3.8+6*events.next();
            cloudStart(wave,t,duration,gap);
            waveOffset=(events.next()-.5)*.08;
            if(v[Wave]>.01) emit(Event{t,1,duration,pan[0]+waveOffset});
        }
        const double surf=wave.envelope(t)*.0006*v[Wave];
        if(t>=fire.next) {
            const double duration=3.7+5*events.next(), gap=8+16*events.next();
            cloudStart(fire,t,duration,gap);
            popNext=t+.2+.25*events.next();
            if(v[Fire]>.01) emit(Event{t,2,duration,pan[1]});
        }
        if(fire.active(t)&&t>=popNext) {
            popAge=0; popDecay=.037+.075*events.next(); popAmp=.30+.55*events.next();
            popPan=(events.next()-.5)*.12; popNext=t+(.15+.85*events.next())/activity;
        }
        const double grain=fire.envelope(t)*v[Fire]/100;
        const double pulse=popAge<.45 && grain>0
            ? -std::expm1(-popAge/(.003+.00005*v[Warmth]))*std::exp(-popAge/popDecay) : 0;
        popAge+=dt;
        const double rustleAmount=v[Rustle]*.0008*std::max(.1,.42+.35*std::sin(tau*t/37+rustlePhase)+.16*std::sin(tau*t/13.2));
        const double waveNoise=noise.white(),grainNoise=noise.white();
        for(int ch=0;ch<2;++ch) {
            double& sample=ch ? out.r : out.l;
            const double x=noise.white(), w=.8*waveNoise+.2*noise.white(), f=.83*grainNoise+.17*noise.white();
            rustleFast[ch]=lerp(rustleFast[ch],x,ar2);
            rustleSlow[ch]=lerp(rustleSlow[ch],x,ar1);
            sample+=(rustleFast[ch]-rustleSlow[ch])*rustleAmount*channelGain(ch,pan[3]);
            waveLow[ch]=lerp(waveLow[ch],w,aw);
            waveMid[ch]=lerp(waveMid[ch],w,am);
            waveHigh[ch]=lerp(waveHigh[ch],w,ah);
            sample+=(1.2*(waveMid[ch]-waveLow[ch])+(.6+(experimental ? .2*std::sin(t*1.1) : 0))*
                (waveHigh[ch]-waveMid[ch]))*surf*channelGain(ch,pan[0]+waveOffset);
            fireFast[ch]=lerp(fireFast[ch],f,fireLP);
            fireSlow[ch]=lerp(fireSlow[ch],f,fireHP);
            if(grain>0) sample+=std::tanh(1.7*(fireFast[ch]-fireSlow[ch]))*(.024+.096*pulse*popAmp)*
                grain*channelGain(ch,pan[1]+popPan);
        }
        if(t>=bird.next) {
            const double duration=1.5+2.6*events.next(), gap=14+22*events.next();
            cloudStart(bird,t,duration,gap);
            chirpNext=t+.1+.3*events.next();
            if(v[Bird]>.01) emit(Event{t,3,duration,pan[2]});
        }
        if(bird.active(t)&&t>=chirpNext) {
            chirpAge=0; chirpDuration=.11+.16*events.next(); chirpFrequency=1350+1150*events.next();
            chirpGlide=(events.next()-.5)*.46; chirpAmp=.45+.55*events.next();
            chirpPan=(events.next()-.5)*.12; chirpNext=t+.28+.55*events.next();
            chirpPhase=0;
        }
        if(bird.active(t)&&chirpAge<chirpDuration) {
            const double u=chirpAge/chirpDuration;
            chirpPhase+=tau*dt*chirpFrequency*(1+chirpGlide*(smooth(u)-.5));
            const double voice=std::sin(chirpPhase)*bell(u)*bird.envelope(t)*chirpAmp*.00055*v[Bird];
            out.l+=voice*channelGain(0,pan[2]+chirpPan);
            out.r+=voice*channelGain(1,pan[2]+chirpPan);
        }
        chirpAge+=dt;
    }
};
}
