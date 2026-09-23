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

    float4 worldPosition =
        uniforms.model * float4(scaledPosition, 1.0);

    out.position =
        uniforms.viewProjection * worldPosition;

    float4 transformedNormal =
        uniforms.model * float4(in.normal, 0.0);

    out.normal = transformedNormal.xyz;
    out.uv = in.uv;

    float intensity =
        computeIntensity(out.normal, uniforms.lightDirection);

    out.intensity = intensity;

    return out;
}