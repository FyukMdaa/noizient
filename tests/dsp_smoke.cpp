#include "overlay.h"
#include <fstream>
#include <cassert>
#include <iostream>
using namespace noizient;
Profile load(bool sleep) {
    std::ifstream f(sleep ? "app/src/main/assets/sleep.bin" : "app/src/main/assets/focus.bin",std::ios::binary);
    return {sleep,{std::istreambuf_iterator<char>(f),std::istreambuf_iterator<char>()}};
}
int main() {
    assert(focusPosition(0).ref==22 && focusPosition(889).ref==45);
    assert(focusPosition(888).blend>.8);
    assert(sleepPosition(0,120).ref==38 && sleepPosition(12,120).ref==50);
    assert(sleepPosition(120,120).ref==338 && sleepPosition(2614,120).ref==2698);
    for(int rate : {44100,48000}) {
        auto fp=load(false), sp=load(true);
        assert(fp.levels.size()==47*913*3 && fp.extra.size()==913*80);
        assert(sp.levels.size()==48*5596*3 && sp.extra.size()==5596*20);
        auto filters=calibrate(sp,24000);
        for(auto b:filters) assert(std::isfinite(b.gain) && b.gain>0);
        std::array<double,3> fm{68,43,39},sm{58,44,57};
        FocusRenderer focus(fp,rate,42,fm), repeat(fp,rate,42,fm);
        SleepRenderer sleep(sp,rate,42,sm,120,filters);
        Layers layers{45,21,20,78,32,19,9,22,38,58,42,80};
        AmbientOverlay overlay(rate,42,layers), same(rate,42,layers), disabled(rate,42,{});
        int events=0, sameEvents=0;
        double peak=0, power=0;
        for(int i=0;i<rate*32;++i) {
            auto a=focus.sample(fm), b=repeat.sample(fm), c=sleep.sample(sm);
            overlay.add(a,layers,0,false,[&](Event e){assert(e.time>=0); ++events;});
            same.add(b,layers,0,false,[&](Event){++sameEvents;});
            Stereo d;
            disabled.add(d,{},0,false,[](Event){assert(false);});
            assert(d.l==0 && d.r==0);
            assert(a.l==b.l && a.r==b.r);
            assert(std::isfinite(a.l) && std::isfinite(a.r) && std::isfinite(c.l) && std::isfinite(c.r));
            peak=std::max({peak,std::abs(a.l),std::abs(a.r),std::abs(c.l),std::abs(c.r)});
            power+=c.l*c.l;
        }
        assert(events>5 && events==sameEvents && power>0);
        std::cout << rate << " Hz: " << events << " events, raw peak " << peak << '\n';
    }
}
