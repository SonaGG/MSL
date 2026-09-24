package gg.sona.msl.bench

import gg.sona.msl.api.SpirvShader
import gg.sona.msl.ir.ResourceKind
import gg.sona.msl.lang.ShaderStage
import org.lwjgl.PointerBuffer
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRPipelineExecutableProperties
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK13
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkComputePipelineCreateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkDeviceCreateInfo
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo
import org.lwjgl.vulkan.VkExtensionProperties
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import org.lwjgl.vulkan.VkPhysicalDevice
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR
import org.lwjgl.vulkan.VkPhysicalDeviceProperties
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo
import org.lwjgl.vulkan.VkPipelineExecutableInfoKHR
import org.lwjgl.vulkan.VkPipelineExecutableInternalRepresentationKHR
import org.lwjgl.vulkan.VkPipelineExecutablePropertiesKHR
import org.lwjgl.vulkan.VkPipelineExecutableStatisticKHR
import org.lwjgl.vulkan.VkPipelineInfoKHR
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo
import org.lwjgl.vulkan.VkQueueFamilyProperties
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES
import org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES
import org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO

class VulkanHarness private constructor(
    private val instance: VkInstance,
    private val device: VkDevice,
    val deviceName: String,
) : AutoCloseable {
    fun statistics(shaders: List<SpirvShader>): Map<String, Map<String, Long>> = MemoryStack.stackPush().use { stack ->
        lastRepresentation.clear()
        val modules = shaders.map { module(stack, it) }
        val layouts = ArrayList<Long>()
        try {
            val pipelineLayout = pipelineLayout(stack, shaders, layouts)
            try {
                val pipeline = if (shaders.size == 1 && shaders[0].reflection.stage == ShaderStage.Kernel) {
                    compute(stack, shaders[0], modules[0], pipelineLayout)
                } else {
                    graphics(stack, shaders, modules, pipelineLayout)
                }
                try {
                    read(stack, pipeline)
                } finally {
                    vkDestroyPipeline(device, pipeline, null)
                }
            } finally {
                vkDestroyPipelineLayout(device, pipelineLayout, null)
            }
        } finally {
            layouts.forEach { vkDestroyDescriptorSetLayout(device, it, null) }
            modules.forEach { vkDestroyShaderModule(device, it, null) }
        }
    }

    private fun check(result: Int, action: String) {
        if (result != VK_SUCCESS) throw IllegalStateException("$action failed: $result")
    }

    private fun module(stack: MemoryStack, shader: SpirvShader): Long {
        val bytes = shader.toByteArray()
        val code = MemoryUtil.memAlloc(bytes.size)
        try {
            code.put(bytes).flip()
            val info = VkShaderModuleCreateInfo.calloc(stack).`sType$Default`().pCode(code)
            val handle = stack.mallocLong(1)
            check(vkCreateShaderModule(device, info, null, handle), "vkCreateShaderModule")
            return handle[0]
        } finally {
            MemoryUtil.memFree(code)
        }
    }

    private fun descriptorType(kind: ResourceKind): Int = when (kind) {
        ResourceKind.UniformBuffer -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
        ResourceKind.StorageBuffer -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
        ResourceKind.SampledTexture -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE
        ResourceKind.StorageTexture -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
        ResourceKind.Sampler -> VK_DESCRIPTOR_TYPE_SAMPLER
    }

    private fun pipelineLayout(stack: MemoryStack, shaders: List<SpirvShader>, layouts: MutableList<Long>): Long {
        val resources = shaders.flatMap { it.reflection.resources }.distinctBy { it.set to it.binding }
        val sets = if (resources.isEmpty()) 0 else resources.maxOf { it.set } + 1
        for (set in 0 until sets) {
            val entries = resources.filter { it.set == set }
            val bindings = VkDescriptorSetLayoutBinding.calloc(entries.size, stack)
            entries.forEachIndexed { index, resource ->
                bindings[index]
                    .binding(resource.binding)
                    .descriptorType(descriptorType(resource.kind))
                    .descriptorCount(if (resource.arraySize > 0) resource.arraySize else RUNTIME_ARRAY)
                    .stageFlags(VK_SHADER_STAGE_ALL)
            }
            val info = VkDescriptorSetLayoutCreateInfo.calloc(stack).`sType$Default`().pBindings(bindings)
            val handle = stack.mallocLong(1)
            check(vkCreateDescriptorSetLayout(device, info, null, handle), "vkCreateDescriptorSetLayout")
            layouts.add(handle[0])
        }
        val info = VkPipelineLayoutCreateInfo.calloc(stack).`sType$Default`()
        if (layouts.isNotEmpty()) info.pSetLayouts(stack.longs(*layouts.toLongArray()))
        val handle = stack.mallocLong(1)
        check(vkCreatePipelineLayout(device, info, null, handle), "vkCreatePipelineLayout")
        return handle[0]
    }

    private fun stage(stack: MemoryStack, target: VkPipelineShaderStageCreateInfo, shader: SpirvShader, module: Long) {
        target.`sType$Default`()
            .stage(
                when (shader.reflection.stage) {
                    ShaderStage.Vertex -> VK_SHADER_STAGE_VERTEX_BIT
                    ShaderStage.Fragment -> VK_SHADER_STAGE_FRAGMENT_BIT
                    ShaderStage.Kernel -> VK_SHADER_STAGE_COMPUTE_BIT
                },
            )
            .module(module)
            .pName(stack.UTF8(shader.reflection.entryPoint))
    }

    private fun compute(stack: MemoryStack, shader: SpirvShader, module: Long, layout: Long): Long {
        val info = VkComputePipelineCreateInfo.calloc(1, stack)
        info[0].`sType$Default`().flags(CAPTURE).layout(layout)
        stage(stack, info[0].stage(), shader, module)
        val handle = stack.mallocLong(1)
        check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle), "vkCreateComputePipelines")
        return handle[0]
    }

    private fun format(type: String): Int {
        val count = Regex("<(\\d) x").find(type)?.groupValues?.get(1)?.toInt() ?: 1
        val element = type.substringAfterLast(' ').trim('>', ' ')
        val formats = when (element) {
            "u32" -> intArrayOf(VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT, VK_FORMAT_R32G32B32A32_UINT)
            "i32" -> intArrayOf(VK_FORMAT_R32_SINT, VK_FORMAT_R32G32_SINT, VK_FORMAT_R32G32B32_SINT, VK_FORMAT_R32G32B32A32_SINT)
            "f16" -> intArrayOf(VK_FORMAT_R16_SFLOAT, VK_FORMAT_R16G16_SFLOAT, VK_FORMAT_R16G16B16_SFLOAT, VK_FORMAT_R16G16B16A16_SFLOAT)
            else -> intArrayOf(VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT)
        }
        return formats[count - 1]
    }

    private fun graphics(stack: MemoryStack, shaders: List<SpirvShader>, modules: List<Long>, layout: Long): Long {
        val stages = VkPipelineShaderStageCreateInfo.calloc(shaders.size, stack)
        shaders.forEachIndexed { index, shader -> stage(stack, stages[index], shader, modules[index]) }
        val vertex = shaders.first { it.reflection.stage == ShaderStage.Vertex }
        val attributes = vertex.reflection.inputs.filter { it.builtin == null }
        val attributeInfo = VkVertexInputAttributeDescription.calloc(attributes.size, stack)
        attributes.forEachIndexed { index, input ->
            attributeInfo[index].location(input.location).binding(0).format(format(input.type)).offset(0)
        }
        val bindingInfo = VkVertexInputBindingDescription.calloc(1, stack)
        bindingInfo[0].binding(0).stride(64).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).`sType$Default`()
        if (attributes.isNotEmpty()) vertexInput.pVertexBindingDescriptions(bindingInfo).pVertexAttributeDescriptions(attributeInfo)
        val fragment = shaders.firstOrNull { it.reflection.stage == ShaderStage.Fragment }
        val colors = fragment?.reflection?.outputs?.filter { it.builtin == null }?.sortedBy { it.location } ?: emptyList()
        val colorCount = if (colors.isEmpty()) 0 else colors.maxOf { it.location } + 1
        val colorFormats = stack.mallocInt(colorCount)
        for (location in 0 until colorCount) {
            colorFormats.put(location, colors.firstOrNull { it.location == location }?.let { format(it.type.replace("f16", "f32")).let(::widen) } ?: VK_FORMAT_R8G8B8A8_UNORM)
        }
        val blend = VkPipelineColorBlendAttachmentState.calloc(colorCount, stack)
        for (i in 0 until colorCount) blend[i].colorWriteMask(0xF)
        val rendering = VkPipelineRenderingCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
            .colorAttachmentCount(colorCount)
            .pColorAttachmentFormats(colorFormats)
            .depthAttachmentFormat(VK_FORMAT_D32_SFLOAT)
        val info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
        info[0].`sType$Default`()
            .pNext(rendering.address())
            .flags(CAPTURE)
            .pStages(stages)
            .pVertexInputState(vertexInput)
            .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).`sType$Default`().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
            .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).`sType$Default`().viewportCount(1).scissorCount(1))
            .pRasterizationState(
                VkPipelineRasterizationStateCreateInfo.calloc(stack).`sType$Default`()
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f),
            )
            .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).`sType$Default`().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
            .pDepthStencilState(VkPipelineDepthStencilStateCreateInfo.calloc(stack).`sType$Default`().depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS))
            .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).`sType$Default`().pAttachments(blend))
            .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).`sType$Default`().pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
            .layout(layout)
        val handle = stack.mallocLong(1)
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, handle), "vkCreateGraphicsPipelines")
        return handle[0]
    }

    private fun widen(format: Int): Int = when (format) {
        VK_FORMAT_R32G32B32_SFLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT
        VK_FORMAT_R32G32B32_UINT -> VK_FORMAT_R32G32B32A32_UINT
        VK_FORMAT_R32G32B32_SINT -> VK_FORMAT_R32G32B32A32_SINT
        else -> format
    }

    private fun read(stack: MemoryStack, pipeline: Long): Map<String, Map<String, Long>> {
        val pipelineInfo = VkPipelineInfoKHR.calloc(stack).`sType$Default`().pipeline(pipeline)
        val count = stack.mallocInt(1)
        check(KHRPipelineExecutableProperties.vkGetPipelineExecutablePropertiesKHR(device, pipelineInfo, count, null), "executable count")
        val properties = VkPipelineExecutablePropertiesKHR.calloc(count[0], stack)
        for (property in properties) property.`sType$Default`()
        check(KHRPipelineExecutableProperties.vkGetPipelineExecutablePropertiesKHR(device, pipelineInfo, count, properties), "executables")
        val result = LinkedHashMap<String, Map<String, Long>>()
        for (index in 0 until count[0]) {
            val executable = VkPipelineExecutableInfoKHR.calloc(stack).`sType$Default`().pipeline(pipeline).executableIndex(index)
            val statCount = stack.mallocInt(1)
            check(KHRPipelineExecutableProperties.vkGetPipelineExecutableStatisticsKHR(device, executable, statCount, null), "statistic count")
            val statistics = VkPipelineExecutableStatisticKHR.calloc(statCount[0], stack)
            for (statistic in statistics) statistic.`sType$Default`()
            check(KHRPipelineExecutableProperties.vkGetPipelineExecutableStatisticsKHR(device, executable, statCount, statistics), "statistics")
            val values = LinkedHashMap<String, Long>()
            for (statistic in statistics) {
                values[statistic.nameString()] = when (statistic.format()) {
                    KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR -> if (statistic.value().b32()) 1L else 0L
                    KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR -> statistic.value().i64()
                    KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_UINT64_KHR -> statistic.value().u64()
                    else -> statistic.value().f64().toLong()
                }
            }
            representations(stack, executable).forEach { (name, text) ->
                val instructions = text.lines().map { it.trim() }.filter { INSTRUCTION.matches(it) }
                values["$name instructions"] = instructions.size.toLong()
                values["$name dynamic"] = IsaCost.dynamic(text) { INSTRUCTION.matches(it) }
                values["$name alu"] = instructions.count { it.startsWith("v_") || it.startsWith("s_") && !it.startsWith("s_waitcnt") && !it.startsWith("s_wait_") && !it.startsWith("s_nop") }.toLong()
                values["$name memory"] = instructions.count { MEMORY.any(it::startsWith) }.toLong()
                values["$name waits"] = instructions.count { it.startsWith("s_waitcnt") || it.startsWith("s_wait_") || it.startsWith("s_nop") }.toLong()
                if (name.startsWith("ISA")) lastRepresentation[properties[index].nameString()] = text
            }
            result["${properties[index].nameString()}#$index"] = values
        }
        return result
    }

    val lastRepresentation = LinkedHashMap<String, String>()

    private fun representations(stack: MemoryStack, executable: VkPipelineExecutableInfoKHR): List<Pair<String, String>> {
        val count = stack.mallocInt(1)
        if (KHRPipelineExecutableProperties.vkGetPipelineExecutableInternalRepresentationsKHR(device, executable, count, null) != VK_SUCCESS) return emptyList()
        val items = VkPipelineExecutableInternalRepresentationKHR.calloc(count[0], stack)
        for (item in items) item.`sType$Default`()
        KHRPipelineExecutableProperties.vkGetPipelineExecutableInternalRepresentationsKHR(device, executable, count, items)
        val buffers = items.map { MemoryUtil.memAlloc(it.dataSize().toInt()) }
        try {
            items.forEachIndexed { index, item ->
                MemoryUtil.memPutAddress(item.address() + VkPipelineExecutableInternalRepresentationKHR.PDATA, MemoryUtil.memAddress(buffers[index]))
            }
            KHRPipelineExecutableProperties.vkGetPipelineExecutableInternalRepresentationsKHR(device, executable, count, items)
            return items.mapIndexed { index, item ->
                val data = buffers[index]
                val bytes = ByteArray(item.dataSize().toInt())
                data.get(0, bytes)
                item.nameString() to String(bytes, Charsets.UTF_8).trimEnd('\u0000')
            }
        } finally {
            buffers.forEach { MemoryUtil.memFree(it) }
        }
    }

    override fun close() {
        vkDestroyDevice(device, null)
        vkDestroyInstance(instance, null)
    }

    companion object {
        private const val RUNTIME_ARRAY = 256
        private const val STACK_KILOBYTES = 4096
        private val INSTRUCTION = Regex("^(v|s|buffer|image|global|scratch|flat|ds|exp|export|tbuffer)_[a-z0-9_]*(\\s.*)?$|^(exp|export)\\s.*")
        private val MEMORY = listOf("buffer_", "image_", "global_", "scratch_", "flat_", "ds_", "tbuffer_", "s_buffer_load", "s_load")
        private const val CAPTURE = KHRPipelineExecutableProperties.VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR or
            KHRPipelineExecutableProperties.VK_PIPELINE_CREATE_CAPTURE_INTERNAL_REPRESENTATIONS_BIT_KHR

        fun create(): VulkanHarness? {
            Configuration.STACK_SIZE.set(STACK_KILOBYTES)
            try {
                VK.getFunctionProvider()
            } catch (_: Throwable) {
                return null
            }
            return MemoryStack.stackPush().use { stack ->
                val app = VkApplicationInfo.calloc(stack).`sType$Default`().apiVersion(VK13.VK_API_VERSION_1_3).pApplicationName(stack.UTF8("msl-bench"))
                val instanceHandle = stack.mallocPointer(1)
                if (vkCreateInstance(VkInstanceCreateInfo.calloc(stack).`sType$Default`().pApplicationInfo(app), null, instanceHandle) != VK_SUCCESS) return@use null
                val instance = VkInstance(instanceHandle[0], VkInstanceCreateInfo.calloc(stack).`sType$Default`().pApplicationInfo(app))
                val physical = pick(stack, instance)
                if (physical == null) {
                    vkDestroyInstance(instance, null)
                    return@use null
                }
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                vkGetPhysicalDeviceProperties(physical, properties)
                val features13 = VkPhysicalDeviceVulkan13Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                val features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES).pNext(features13.address())
                val features11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES).pNext(features12.address())
                val executable = VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR.calloc(stack).`sType$Default`().pNext(features11.address())
                val features = VkPhysicalDeviceFeatures2.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(executable.address())
                vkGetPhysicalDeviceFeatures2(physical, features)
                if (!executable.pipelineExecutableInfo()) {
                    vkDestroyInstance(instance, null)
                    return@use null
                }
                val families = stack.mallocInt(1)
                vkGetPhysicalDeviceQueueFamilyProperties(physical, families, null)
                val familyProperties = VkQueueFamilyProperties.calloc(families[0], stack)
                vkGetPhysicalDeviceQueueFamilyProperties(physical, families, familyProperties)
                val family = (0 until families[0]).first { familyProperties[it].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0 }
                val queue = VkDeviceQueueCreateInfo.calloc(1, stack)
                queue[0].`sType$Default`().queueFamilyIndex(family).pQueuePriorities(stack.floats(1f))
                val extensions: PointerBuffer = stack.pointers(stack.UTF8(KHRPipelineExecutableProperties.VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME))
                val deviceInfo = VkDeviceCreateInfo.calloc(stack).`sType$Default`()
                    .pNext(features.address())
                    .pQueueCreateInfos(queue)
                    .ppEnabledExtensionNames(extensions)
                val deviceHandle = stack.mallocPointer(1)
                if (vkCreateDevice(physical, deviceInfo, null, deviceHandle) != VK_SUCCESS) {
                    vkDestroyInstance(instance, null)
                    return@use null
                }
                VulkanHarness(instance, VkDevice(deviceHandle[0], physical, deviceInfo, VK13.VK_API_VERSION_1_3), properties.deviceNameString())
            }
        }

        private fun pick(stack: MemoryStack, instance: VkInstance): VkPhysicalDevice? {
            val count = stack.mallocInt(1)
            vkEnumeratePhysicalDevices(instance, count, null)
            if (count[0] == 0) return null
            val handles = stack.mallocPointer(count[0])
            vkEnumeratePhysicalDevices(instance, count, handles)
            val devices = (0 until count[0]).map { VkPhysicalDevice(handles[it], instance) }
            return devices.filter { supports(stack, it) }.maxByOrNull { device ->
                val properties = VkPhysicalDeviceProperties.calloc(stack)
                vkGetPhysicalDeviceProperties(device, properties)
                if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) 1 else 0
            }
        }

        private fun supports(stack: MemoryStack, device: VkPhysicalDevice): Boolean {
            val count = stack.mallocInt(1)
            vkEnumerateDeviceExtensionProperties(device, null as String?, count, null)
            val extensions = VkExtensionProperties.calloc(count[0], stack)
            vkEnumerateDeviceExtensionProperties(device, null as String?, count, extensions)
            return extensions.any { it.extensionNameString() == KHRPipelineExecutableProperties.VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME }
        }
    }
}
