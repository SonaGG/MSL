#include <metal_stdlib>
using namespace metal;

struct Particle {
    packed_float3 position;
    float mass;
    float3 velocity;
};

struct Params {
    float deltaTime;
    uint count;
};

kernel void simulate(device Particle* particles [[buffer(0)]],
                     constant Params& params [[buffer(1)]],
                     device atomic_uint* counter [[buffer(2)]],
                     threadgroup float* scratch [[threadgroup(0)]],
                     uint gid [[thread_position_in_grid]],
                     uint lid [[thread_index_in_threadgroup]],
                     uint3 groupSize [[threads_per_threadgroup]])
{
    if (gid >= params.count) {
        return;
    }
    Particle p = particles[gid];
    p.velocity += float3(0.0, -9.81, 0.0) * params.deltaTime;
    float3 position = p.position;
    position += p.velocity * params.deltaTime;
    p.position = position;
    particles[gid] = p;
    scratch[lid] = p.mass;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    float sum = 0.0;
    for (uint i = 0; i < groupSize.x; ++i) {
        sum += scratch[i];
    }
    if (sum > 10.0) {
        atomic_fetch_add_explicit(counter, 1u, memory_order_relaxed);
    }
}
