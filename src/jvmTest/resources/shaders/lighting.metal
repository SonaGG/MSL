#include <metal_stdlib>
using namespace metal;

struct Camera {
    float4x4 viewProjection;
    float4x4 model;
    float3 lightDirection;
    float scale;
};

struct VertexIn {
    float3 position [[attribute(0)]];
    float3 normal [[attribute(1)]];
    float2 uv [[attribute(2)]];
};

struct VertexOut {
    float4 position [[position]];
    float3 normal;
    float2 uv;
    float light;
};

vertex VertexOut litVertex(VertexIn input [[stage_in]], constant Camera& camera [[buffer(0)]])
{
    VertexOut output;
    float3 scaled = input.position * float3(camera.scale, camera.scale, camera.scale);
    float4 world = camera.model * float4(scaled, 1.0);
    output.position = camera.viewProjection * world;
    float3 normal = normalize((camera.model * float4(input.normal, 0.0)).xyz);
    output.normal = normal;
    output.uv = input.uv * 2.0 + float2(0.0, 1.0) * 0.0;
    float ndl = dot(normal, -camera.lightDirection);
    output.light = ndl >= 0.0 ? ndl : 0.0;
    return output;
}

fragment float4 litFragment(VertexOut input [[stage_in]], texture2d<float> albedo [[texture(0)]], sampler linear [[sampler(0)]])
{
    float4 color = albedo.sample(linear, input.uv);
    float3 lit = color.rgb * (input.light * 0.8 + 0.2);
    return float4(lit * 1.0 + 0.0, color.a);
}
