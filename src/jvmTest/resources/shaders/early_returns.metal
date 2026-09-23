#include <metal_stdlib>
using namespace metal;
inline float f(float mode, float x)
{
    if (mode == 1.0) return exp(x);
    if (mode == 2.0) {
        float s = x * 2.0;
        return exp(-s * s);
    }
    return x;
}
fragment float4 earlyReturns(float4 p [[position]])
{
    float4 c = p;
    if (p.z != 0.0) {
        c.x = mix(c.y, c.x, saturate(f(p.w, p.x)));
    }
    return c;
}
