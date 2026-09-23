#include <metal_stdlib>
#include <simd/simd.h>
using namespace metal;

#define SQUARE(x) ((x) * (x))
#if defined(__METAL_VERSION__) && __METAL_VERSION__ >= 200
#define HAS_METAL_2 1
#else
#define HAS_METAL_2 0
#endif

namespace shading {
    constant float kPi = 3.14159265;

    enum class Mode : uint {
        Lit = 0,
        Unlit = 1,
        Debug = 2,
    };

    struct Ray {
        float3 origin;
        float3 direction;

        Ray(float3 o, float3 d) : origin(o), direction(normalize(d)) {}

        float3 at(float t) const { return origin + direction * t; }
    };

    template <typename T, int N>
    struct Stack {
        T items[N];
        int size;

        void push(T value) {
            if (size < N) {
                items[size++] = value;
            }
        }

        T top() const { return items[max(size - 1, 0)]; }
    };

    struct Complex {
        float re;
        float im;

        Complex operator+(Complex other) const { return Complex{re + other.re, im + other.im}; }
        Complex operator*(Complex other) const { return Complex{re * other.re - im * other.im, re * other.im + im * other.re}; }
    };

    inline float luminance(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
    inline float luminance(half3 c) { return luminance(float3(c)); }

    template <typename T>
    inline T lerpValue(T a, T b, float t = 0.5) { return a + (b - a) * t; }

    constexpr int factorial(int n)
    {
        int result = 1;
        for (int i = 2; i <= n; ++i) {
            result *= i;
        }
        return result;
    }
}

typedef float4 Color;
using Vec3 = vec<float, 3>;

struct Settings {
    shading::Mode mode;
    float exposure;
    array<float4, 3> palette;
};

static_assert(sizeof(float4) == 16, "float4 must be 16 bytes");

fragment Color languageFragment(float4 position [[position]], constant Settings& settings [[buffer(0)]])
{
    using namespace shading;
    Ray ray(float3(0.0), float3(position.xy, 1.0));
    Vec3 point = ray.at(2.0);
    Stack<float, 4> stack = {};
    for (int i = 0; i < 6; ++i) {
        stack.push(float(i) * kPi);
    }
    Complex a = {1.0, 2.0};
    Complex b = {0.5, -1.0};
    Complex c = a * b + a;
    float value = luminance(point) + luminance(half3(point)) + stack.top() + c.re + c.im;
    value += lerpValue(1.0f, 3.0f) + lerpValue(1.0f, 3.0f, 0.25);
    value += float(factorial(4)) + SQUARE(value) * float(HAS_METAL_2);
    uint bits = as_type<uint>(floor(value));
    float2 halves = float2(as_type<half2>(bits));
    int3 swizzled = int3(1, 2, 3).zyx;
    float4 color = settings.palette[uint(settings.mode) % 3];
    color.xy = halves;
    color.zw += float2(swizzled.xy);
    switch (settings.mode) {
        case Mode::Unlit:
            return color;
        case Mode::Debug:
            return float4(point, 1.0);
        default:
            break;
    }
    metal::float3x3 basis = metal::float3x3(1.0);
    color.rgb = basis * color.rgb * settings.exposure;
    return color;
}
