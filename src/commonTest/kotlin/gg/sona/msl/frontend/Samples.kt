package gg.sona.msl.frontend

object Samples {
    val BASIC_VERTEX = """
        #include <metal_stdlib>
        using namespace metal;

        struct VertexInput {
            float3 position [[attribute(0)]];
            float3 normal   [[attribute(1)]];
            float2 uv       [[attribute(2)]];
        };

        struct VertexOutput {
            float4 position [[position]];
            float3 normal;
            float2 uv;
            float  intensity;
        };

        struct Uniforms {
            float4x4 model;
            float4x4 viewProjection;
            float3 lightDirection;
            float scale;
        };

        float computeIntensity(float3 normal, float3 lightDirection)
        {
            float d = dot(normal, lightDirection);
            if (d < 0.0) {
                return 0.0;
            }
            return d;
        }

        vertex VertexOutput vertexMain(
            VertexInput in [[stage_in]],
            constant Uniforms& uniforms [[buffer(0)]])
        {
            VertexOutput out;
            float3 scaledPosition = in.position * uniforms.scale;
            float4 worldPosition = uniforms.model * float4(scaledPosition, 1.0);
            out.position = uniforms.viewProjection * worldPosition;
            float4 transformedNormal = uniforms.model * float4(in.normal, 0.0);
            out.normal = transformedNormal.xyz;
            out.uv = in.uv;
            out.intensity = computeIntensity(out.normal, uniforms.lightDirection);
            return out;
        }
    """.trimIndent()

    val HELL = """
#include <metal_stdlib>
using namespace metal;

constant uint STEPS = 160u;
constant bool WARP = true;
constant uint FIB[16] = {0u, 1u, 1u, 2u, 3u, 5u, 8u, 13u, 21u, 34u, 55u, 89u, 144u, 233u, 377u, 610u};

template<int D> inline float3 oct(float3 p, float3x3 m) {
    float a = 1.0f; float3 acc = float3(0.0f);
    for (int i = D; i > 0; --i) {
        float3 q = sin(p * (1.7f + float(i) * 0.31f) + cos(p.yzx * 2.3f - float(FIB[i + 8] % 7u)));
        a *= 0.53f; acc += q * a; p = m * (q.zxy * 2.07f + p);
    }
    return acc;
}

template<typename T> inline T smin(T a, T b, T k) { T h = saturate(0.5f + 0.5f * (b - a) / k); return mix(b, a, h) - k * h * (1.0f - h); }
inline uint pcg(uint v) { uint s = v * 747796405u + 2891336453u; uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u; return (w >> 22u) ^ w; }
inline float4 qmul(float4 a, float4 b) { return float4(a.x * b.x - dot(a.yzw, b.yzw), a.x * b.yzw + b.x * a.yzw + cross(a.yzw, b.yzw)); }

inline float3x3 rot(float3 ax, float a) {
    ax = normalize(ax); float s = sin(a), c = cos(a), o = 1.0f - c;
    return float3x3(float3(o * ax.x * ax.x + c, o * ax.x * ax.y + ax.z * s, o * ax.z * ax.x - ax.y * s),
                    float3(o * ax.x * ax.y - ax.z * s, o * ax.y * ax.y + c, o * ax.y * ax.z + ax.x * s),
                    float3(o * ax.z * ax.x + ax.y * s, o * ax.y * ax.z - ax.x * s, o * ax.z * ax.z + c));
}

template<int K> inline float bulb(float3 p, thread float3& trap) {
    float3 z = p; float dr = 1.0f, r = 0.0f;
    for (int i = 0; i < 12; ++i) {
        r = length(z); if (r > 2.0f) break;
        float th = acos(clamp(z.z / r, -1.0f, 1.0f)) * float(K), ph = atan2(z.y, z.x) * float(K);
        dr = pow(r, float(K - 1)) * float(K) * dr + 1.0f;
        z = pow(r, float(K)) * float3(sin(th) * cos(ph), sin(ph) * sin(th), cos(th)) + p;
        trap = min(trap, abs(z));
    }
    return 0.5f * log(max(r, 1e-6f)) * r / dr;
}

inline float julia(float3 p, float4 c, thread float3& trap) {
    float4 z = float4(p, 0.0f); float md2 = 1.0f, mz2 = dot(z, z);
    for (int i = 0; i < 11; ++i) {
        md2 *= 4.0f * mz2; z = qmul(z, z) + c; mz2 = dot(z, z);
        trap = min(trap, z.xyz * z.xyz);
        if (mz2 > 4.0f) break;
    }
    return 0.25f * sqrt(mz2 / md2) * log(mz2);
}

struct Hit { float d; float3 trap; uint id; };
struct Params { float time; uint2 size; uint frame; };

inline Hit scene(float3 p, float t) {
    Hit h; h.trap = float3(1e9f);
    float3x3 m = rot(float3(sin(t), 1.0f, cos(t * 0.7f)), t * 0.3f);
    float3 w = WARP ? p + 0.15f * oct<6>(p, m) : p;
    float a = bulb<8>(m * w, h.trap);
    float b = julia(w.zxy * 1.3f, float4(sin(t) * 0.4f, cos(t * 1.3f) * 0.5f, 0.2f, -0.3f), h.trap) / 1.3f;
    float3 g = fract(w * 0.5f) * 2.0f - 1.0f;
    float c = length(max(abs(g) - 0.3f, 0.0f)) - 0.05f;
    float e = bulb<5>(w.yzx * 0.8f, h.trap) / 0.8f;
    h.d = max(smin(smin(smin(a, b, 0.2f), c, 0.2f), e, 0.2f), length(p) - 1.6f);
    h.id = c < min(a, b) ? 2u : (b < a ? 1u : 0u);
    return h;
}

inline float3 nrm(float3 p, float t) {
    const float2 e = float2(1.0f, -1.0f) * 5e-4f;
    return normalize(e.xyy * scene(p + e.xyy, t).d + e.yyx * scene(p + e.yyx, t).d + e.yxy * scene(p + e.yxy, t).d + e.xxx * scene(p + e.xxx, t).d);
}

kernel void hell(texture2d<float, access::write> out [[texture(0)]], texture2d<float, access::read> prev [[texture(1)]],
                 device atomic_uint* hist [[buffer(0)]], constant Params& P [[buffer(1)]],
                 uint2 gid [[thread_position_in_grid]], uint lid [[thread_index_in_threadgroup]],
                 uint sl [[thread_index_in_simdgroup]], uint2 tgs [[threads_per_threadgroup]]) {
    threadgroup float4 tile[256];
    bool valid = all(gid < P.size);
    uint seed = pcg(gid.x + pcg(gid.y + pcg(P.frame)));
    float2 uv = (float2(gid) + float2(float(seed & 0xFFFFu), float(seed >> 16)) / 65536.0f - 0.5f * float2(P.size)) / float(P.size.y); uv.y = -uv.y;
    float t = P.time;
    float3 ro = float3(3.2f * sin(t * 0.2f), 1.2f * cos(t * 0.13f), 3.2f * cos(t * 0.2f));
    float3 fw = normalize(-ro), rt = normalize(cross(float3(0.0f, 1.0f, 0.0f), fw)), up = cross(fw, rt);
    float3 rd = normalize(uv.x * rt + uv.y * up + 1.6f * fw);
    float dist = 0.0f; Hit h = scene(ro, t);
    for (uint i = 0; i < STEPS; ++i) { h = scene(ro + rd * dist, t); if (abs(h.d) < 1e-4f * dist || dist > 12.0f) break; dist += h.d * 0.7f; }
    float3 col = float3(0.02f, 0.01f, 0.04f) * (1.0f - uv.y);
    if (dist < 12.0f) {
        float3 p = ro + rd * dist, n = nrm(p, t), l = normalize(float3(0.6f, 0.8f, -0.4f));
        float sh = 1.0f, st = 0.01f, ao = 0.0f;
        for (int i = 0; i < 32 && st < 6.0f; ++i) { float d = scene(p + n * 1e-3f + l * st, t).d; sh = min(sh, 12.0f * d / st); st += clamp(d, 0.01f, 0.3f); }
        for (int i = 1; i <= 6; ++i) { float hh = 0.03f * float(i * i); ao += (hh - scene(p + n * hh, t).d) / exp2(float(i)); }
        float3 base = 0.5f + 0.5f * cos(float3(0.0f, 2.1f, 4.2f) + h.trap.x * 3.0f + h.trap.y * 5.0f + float(h.id) * 1.7f);
        col = base * (saturate(dot(n, l)) * saturate(sh) + 0.15f) * saturate(1.0f - ao * 2.0f) + pow(saturate(dot(n, normalize(l - rd))), 64.0f) * saturate(sh);
        col = mix(col, float3(0.02f, 0.01f, 0.04f), 1.0f - exp(-0.02f * dist * dist));
    }
    uint cnt = min(tgs.x * tgs.y, 256u);
    tile[min(lid, 255u)] = float4(col, dot(col, float3(0.2126f, 0.7152f, 0.0722f)));
    threadgroup_barrier(mem_flags::mem_threadgroup);
    float4 acc = float4(0.0f);
    for (int dy = -1; dy <= 1; ++dy) for (int dx = -1; dx <= 1; ++dx)
        acc += tile[clamp(int(lid) + dy * int(tgs.x) + dx, 0, int(cnt) - 1)] * float((2 - abs(dx)) * (2 - abs(dy)));
    acc /= 16.0f;
    float mx = simd_max(acc.w), sm = simd_sum(acc.w);
    float4 nb = simd_shuffle_xor(acc, 1u);
    if (sl == 0u) atomic_fetch_add_explicit(&hist[min(uint(sm * 8.0f), 255u)], 1u, memory_order_relaxed);
    if (valid) {
        float3 c = mix(acc.rgb, nb.rgb, 0.1f) / (1.0f + mx);
        c = pow(saturate(c * (2.51f * c + 0.03f) / (c * (2.43f * c + 0.59f) + 0.14f)), float3(1.0f / 2.2f));
        out.write(float4(mix(c, prev.read(gid).rgb, P.frame > 0u ? 0.8f : 0.0f), 1.0f), gid);
    }
}
    """.trimIndent()
}
