#include <metal_stdlib>
using namespace metal;

fragment half4 mathFragment(float4 position [[position]], bool front [[front_facing]])
{
    float3 v = normalize(position.xyz);
    float3 n = float3(0.0, 1.0, 0.0);
    float a = dot(v, n) + length(v) + distance(v, n);
    float3 r = reflect(v, n) + refract(v, n, 1.33) + cross(v, n) + faceforward(n, v, n);
    float s = sin(a) + cos(a) + tan(a) + asin(0.5) + acos(0.5) + atan(a) + atan2(a, 2.0);
    s += sinh(a) + cosh(a) + tanh(a) + asinh(a) + acosh(2.0) + atanh(0.5);
    s += exp(a) + exp2(a) + exp10(a) + log(a) + log2(a) + log10(a) + pow(a, 2.0) + powr(a, 2.0);
    s += sqrt(a) + rsqrt(a) + fabs(a) + floor(a) + ceil(a) + round(a) + rint(a) + trunc(a) + fract(a);
    s += fmin(a, s) + fmax(a, s) + fmod(a, 3.0) + copysign(a, -1.0) + fdim(a, 1.0) + fma(a, s, 1.0);
    s += clamp(a, 0.0, 1.0) + mix(a, s, 0.5) + step(0.5, a) + smoothstep(0.0, 1.0, a) + saturate(a) + sign(a);
    s += sinpi(a) + cospi(a) + tanpi(a) + ldexp(a, 2) + min3(a, s, 1.0) + max3(a, s, 1.0) + median3(a, s, 1.0);
    float whole;
    s += modf(a, whole) + whole;
    int exponent;
    s += frexp(a, exponent) + float(exponent);
    float c;
    s += sincos(a, c) + c;
    bool3 nans = isnan(r) || isinf(r);
    if (any(nans) || !all(isfinite(r)) || signbit(s) || isnormal(s)) {
        s += 1.0;
    }
    int i = int(s);
    uint u = uint(abs(i));
    i += abs(i) + clamp(i, -2, 2) + min(i, 3) + max(i, -3) + popcount(i) + clz(i) + ctz(i) + reverse_bits(i);
    u += absdiff(u, 3u) + addsat(u, 5u) + subsat(u, 5u) + hadd(u, 2u) + rhadd(u, 2u) + mulhi(u, 7u) + rotate(u, 3u);
    u += extract_bits(u, 2u, 4u) + insert_bits(u, 3u, 4u, 2u) + mad24(u, 2u, 1u) + mul24(u, u);
    int si = addsat(i, 1000) + subsat(i, -1000) + hadd(i, -2);
    ushort small = ushort(u);
    small = popcount(small) + clz(small);
    uint packed = pack_float_to_unorm4x8(float4(r, 1.0)) ^ pack_float_to_snorm2x16(float2(s, a));
    float4 unpacked = unpack_unorm4x8_to_float(packed) + float4(unpack_snorm2x16_to_float(packed), 0.0, 0.0);
    float2x2 m2 = float2x2(float2(1.0, 2.0), float2(3.0, 4.0));
    float3x3 m3 = float3x3(1.0);
    float4x4 m4 = float4x4(float4(1.0), float4(2.0), float4(3.0), float4(4.0));
    float det = determinant(m2) + determinant(m3) + determinant(m4);
    float3 t = transpose(m3) * r;
    float dx = dfdx(a) + dfdy(a) + fwidth(a);
    float result = s + float(i + si) + float(u) + float(small) + unpacked.x + det + t.x + dx + select(1.0, 2.0, front);
    return half4(half(result), half(a), 0.0h, 1.0h);
}
