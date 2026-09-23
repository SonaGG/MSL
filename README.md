# MSL

A compiler for the Metal Shading Language (MSL), built with Kotlin Multiplatform.

MSL is designed to provide a portable compiler infrastructure with first-party support for modern GPU intermediate representations and shader targets.

## Supported Targets

| Target        | Status       | Support     |
|---------------|--------------|-------------|
| **SPIR-V**    | ✅ Supported | First-party |
| **DXIL**      | ✅ Supported | First-party |
| **Metal AIR** | 🚧 WIP       | First-party |

### SPIR-V

First-class SPIR-V compilation support for Vulkan-compatible workflows.

### DXIL

First-class DXIL compilation support for DirectX shader pipelines.

### Metal AIR

Native Metal AIR support is currently under active development.

## Licensing

This project is licensed under Apache License 2.0. For more details, read [LICENSE.md](/LICENSE.md)