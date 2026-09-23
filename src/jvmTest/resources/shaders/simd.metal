#include <metal_stdlib>
using namespace metal;

kernel void reduce(device float* data [[buffer(0)]],
                   device uint* masks [[buffer(1)]],
                   uint gid [[thread_position_in_grid]],
                   uint lane [[thread_index_in_simdgroup]],
                   uint width [[threads_per_simdgroup]])
{
    float value = data[gid];
    float total = simd_sum(value) + simd_max(value) + simd_prefix_exclusive_sum(value);
    total += simd_shuffle(value, ushort(0)) + simd_shuffle_down(value, ushort(1)) + simd_shuffle_xor(value, ushort(2));
    total += simd_broadcast_first(value) + quad_shuffle_xor(value, ushort(1)) + quad_broadcast(value, ushort(0));
    bool any = simd_any(value > 0.0);
    bool all = simd_all(value > 0.0);
    ulong ballot = simd_ballot(value > 1.0);
    masks[gid] = uint(ballot) + uint(any) + uint(all) + (simd_is_first() ? 1u : 0u) + lane + width;
    uint bits = simd_or(uint(gid)) + simd_and(uint(gid));
    data[gid] = total + float(bits);
    simdgroup_barrier(mem_flags::mem_none);
}
