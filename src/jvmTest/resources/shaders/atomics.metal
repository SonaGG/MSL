#include <metal_stdlib>
using namespace metal;

struct Counters {
    atomic_uint hits;
    atomic_int balance;
    atomic_uint flags;
};

kernel void accumulate(device Counters& counters [[buffer(0)]],
                       device atomic_float* total [[buffer(1)]],
                       device atomic_uint* histogram [[buffer(2)]],
                       device const float* samples [[buffer(3)]],
                       threadgroup atomic_uint* shared [[threadgroup(0)]],
                       uint gid [[thread_position_in_grid]],
                       uint lid [[thread_index_in_threadgroup]])
{
    if (lid == 0) {
        atomic_store_explicit(shared, 0u, memory_order_relaxed);
    }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    float value = samples[gid];
    uint bucket = min(uint(value * 16.0), 15u);
    atomic_fetch_add_explicit(&histogram[bucket], 1u, memory_order_relaxed);
    atomic_fetch_add_explicit(&counters.hits, 1u, memory_order_relaxed);
    atomic_fetch_sub_explicit(&counters.balance, int(bucket), memory_order_relaxed);
    atomic_fetch_or_explicit(&counters.flags, 1u << bucket, memory_order_relaxed);
    atomic_fetch_and_explicit(&counters.flags, ~0u, memory_order_relaxed);
    atomic_fetch_xor_explicit(&counters.flags, 0u, memory_order_relaxed);
    atomic_fetch_max_explicit(&counters.balance, -5, memory_order_relaxed);
    atomic_fetch_min_explicit(&counters.hits, 1000u, memory_order_relaxed);
    atomic_fetch_add_explicit(total, value, memory_order_relaxed);
    uint expected = 0u;
    atomic_compare_exchange_weak_explicit(&counters.flags, &expected, 7u, memory_order_relaxed, memory_order_relaxed);
    uint previous = atomic_exchange_explicit(&histogram[0], atomic_load_explicit(&histogram[1], memory_order_relaxed), memory_order_relaxed);
    uint local = atomic_fetch_add_explicit(shared, previous, memory_order_relaxed);
    atomic_fetch_max_explicit(shared, local, memory_order_relaxed);
    uint seen = 1u;
    atomic_compare_exchange_weak_explicit(shared, &seen, 2u, memory_order_relaxed, memory_order_relaxed);
    threadgroup_barrier(mem_flags::mem_device | mem_flags::mem_threadgroup);
}
