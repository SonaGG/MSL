#include <metal_stdlib>
using namespace metal;

template<int N> struct Pow {
    template<typename T> static T eval(T x) { return x * Pow<N-1>::eval(x); }
};
template<> struct Pow<0> {
    template<typename T> static T eval(T x) { return T(1); }
};

template<typename T, int N> struct Poly {
    T c[N];
    T eval(T x) const {
        T r = c[N-1];
        for (int i=N-2; i>=0; --i) r = r*x + c[i];
        return r;
    }
};

template<typename T> T sq(T x) { return x*x; }
template<typename T> T madd(T a,T b,T c) { return a*b+c; }

template<typename T, int N> struct VecOp {
    static T run(T x) {
        if constexpr (N == 0) return x;
        else return VecOp<T,N-1>::run(madd(x,T(N),T(N*N)));
    }
};

template<typename T> struct Box {
    T x;
    template<int N> T transform() const {
        T a = Pow<N>::eval(x);
        return a + VecOp<T,N>::run(x);
    }
};

template<int N, typename T>
T reduction(T x) {
    if constexpr (N == 0) return x;
    else return reduction<N-1>(x + sq(T(N)) * T(0.01));
}

struct Params {
    float4x4 m;
    float scale;
    uint count, mode, iterations;
};

template<int MODE>
float dispatch(float x) {
    Box<float> b{x};
    if constexpr (MODE == 0) return b.transform<2>();
    if constexpr (MODE == 1) return b.transform<3>();
    return reduction<5>(b.transform<4>());
}

kernel void torture(
    device float4* in [[buffer(0)]],
    device float4* out [[buffer(1)]],
    constant Params& p [[buffer(2)]],
    uint id [[thread_position_in_grid]])
{
    if (id >= p.count) return;

    float4 v = in[id];
    Poly<float,4> poly = {{1.0, -2.0, 3.0, 0.5}};
    float x = poly.eval(v.x * p.scale);

    float a;
    switch (p.mode) {
        case 0: a = dispatch<0>(x); break;
        case 1: a = dispatch<1>(x); break;
        default: a = dispatch<2>(x); break;
    }

    float dead = Pow<6>::eval(2.0f);
    float acc = 0.0;

    for (uint i=0; i<p.iterations; ++i) {
        float q = reduction<3>(a + float(i));
        if (q > 100.0) { acc += q; break; }
        if ((i & 1u) != 0u) continue;
        acc = madd(acc, 0.9f, sq(q) * 0.01f);
    }

    float4 r = p.m * float4(v.xyz, 1.0);
    bool f = acc > 0.0 && v.y < a;
    out[id] = float4(r.xyz + (f ? acc : -acc), a);
}
