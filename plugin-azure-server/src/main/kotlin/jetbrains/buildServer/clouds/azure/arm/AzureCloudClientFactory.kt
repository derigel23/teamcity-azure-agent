package jetbrains.buildServer.clouds.azure.arm

import jetbrains.buildServer.clouds.CloudClientParameters
import jetbrains.buildServer.clouds.CloudImageParameters
import jetbrains.buildServer.clouds.CloudRegistrar
import jetbrains.buildServer.clouds.CloudState
import jetbrains.buildServer.clouds.azure.AzureCloudImagesHolder
import jetbrains.buildServer.clouds.azure.AzureProperties
import jetbrains.buildServer.clouds.azure.AzureUtils
import jetbrains.buildServer.clouds.azure.arm.connector.AzureApiConnectorFactory
import jetbrains.buildServer.clouds.azure.arm.throttler.AzureThrottlerSchedulersProvider
import jetbrains.buildServer.clouds.base.AbstractCloudClientFactory
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo
import jetbrains.buildServer.serverSide.AgentDescription
import jetbrains.buildServer.serverSide.BuildAgentManagerEx
import jetbrains.buildServer.serverSide.PropertiesProcessor
import jetbrains.buildServer.web.openapi.PluginDescriptor

/**
 * Constructs Azure ARM cloud clients.
 */
class AzureCloudClientFactory(
    cloudRegistrar: CloudRegistrar,
    private val myPluginDescriptor: PluginDescriptor,
    private val myImagesHolder: AzureCloudImagesHolder,
    private val myApiConnectorFactory: AzureApiConnectorFactory,
    private val mySchedulersProvider: AzureThrottlerSchedulersProvider,
    private val myBuildAgentManager: BuildAgentManagerEx
)
    : AbstractCloudClientFactory<AzureCloudImageDetails, AzureCloudClient>(cloudRegistrar) {

    override fun createNewClient(state: CloudState,
                                 images: Collection<AzureCloudImageDetails>,
                                 params: CloudClientParameters): AzureCloudClient {
        return createNewClient(state, params, arrayOf())
    }

    override fun createNewClient(state: CloudState,
                                 params: CloudClientParameters,
                                 errors: Array<TypedCloudErrorInfo>): AzureCloudClient {
        val parameters = params.listParameterNames().associateWith {
            params.getParameter(it)!!
        }

        val apiConnector = myApiConnectorFactory.create(parameters, state.profileId)

        val azureCloudClient = AzureCloudClient(params, apiConnector, myImagesHolder, mySchedulersProvider, myBuildAgentManager)
        azureCloudClient.updateErrors(*errors)

        apiConnector.start()

        return azureCloudClient
    }

    override fun getTypeDescription(): String = """
        Agents are hosted on Azure Virtual Machines that provide unique versions of Microsoft Windows. This is customizable solution that can be tailored to various TeamCity projects.
    """.trimIndent()

    override fun getProfileIconUrl(): String = myPluginDescriptor.getPluginResourcesPath("icon.svg")

    override fun parseImageData(params: CloudClientParameters): Collection<AzureCloudImageDetails> {
        if (!params.getParameter(CloudImageParameters.SOURCE_IMAGES_JSON).isNullOrEmpty()) {
            return AzureUtils.parseImageData(AzureCloudImageDetails::class.java, params)
        }

        return params.cloudImages.map { param ->
            AzureCloudImageDetails(
                    param.id,
                    param.getParameter(AzureConstants.DEPLOY_TARGET)?.let(AzureCloudDeployTarget::valueOf),
                    param.getParameter(AzureConstants.REGION),
                    param.getParameter(AzureConstants.GROUP_ID),
                    param.getParameter(AzureConstants.IMAGE_TYPE)?.let(AzureCloudImageType::valueOf),
                    param.getParameter(AzureConstants.IMAGE_URL),
                    param.getParameter(AzureConstants.IMAGE_ID),
                    param.getParameter(AzureConstants.INSTANCE_ID),
                    param.getParameter(AzureConstants.OS_TYPE),
                    param.getParameter(AzureConstants.NETWORK_ID),
                    param.getParameter(AzureConstants.SUBNET_ID),
                    param.getParameter(AzureConstants.VM_NAME_PREFIX),
                    param.getParameter(AzureConstants.VM_SIZE),
                    (param.getParameter(AzureConstants.VM_PUBLIC_IP) ?: "").toBoolean(),
                    (param.getParameter(AzureConstants.MAX_INSTANCES_COUNT) ?: "1").toInt(),
                    param.getParameter(AzureConstants.VM_USERNAME),
                    param.getParameter(AzureConstants.STORAGE_ACCOUNT_TYPE),
                    param.getParameter(AzureConstants.TEMPLATE),
                    param.getParameter(AzureConstants.NUMBER_CORES),
                    param.getParameter(AzureConstants.MEMORY),
                    param.getParameter(AzureConstants.STORAGE_ACCOUNT),
                    param.getParameter(AzureConstants.REGISTRY_USERNAME),
                    param.agentPoolId,
                    param.getParameter(AzureConstants.PROFILE_ID),
                    (param.getParameter(AzureConstants.REUSE_VM) ?: "").toBoolean(),
                    param.getParameter(AzureConstants.CUSTOM_ENVIRONMENT_VARIABLES),
                    param.getParameter(AzureConstants.CUSTOM_TAGS),
                    param.getParameter(AzureConstants.SPOT_VM)?.toBoolean(),
                    param.getParameter(AzureConstants.ENABLE_SPOT_PRICE)?.toBoolean(),
                    param.getParameter(AzureConstants.SPOT_PRICE)?.toInt(),
                    param.getParameter(AzureConstants.ENABLE_ACCELERATED_NETWORKING)?.toBoolean(),
                    param.getParameter(AzureConstants.DISABLE_TEMPLATE_MODIFICATION)?.toBoolean(),
                    param.getParameter(AzureConstants.USER_ASSIGNED_IDENTITY) ?: "",
                    param.getParameter(AzureConstants.ENABLE_SYSTEM_ASSIGNED_IDENTITY)?.toBoolean()
            )
        }.apply {
            AzureUtils.setPasswords(AzureCloudImageDetails::class.java, params, this)
        }
    }

    override fun checkClientParams(params: CloudClientParameters): Array<TypedCloudErrorInfo> {
        return emptyArray()
    }

    override fun getCloudCode() = AzureConstants.CLOUD_CODE

    override fun getDisplayName(): String {
        return "Azure Resource Manager"
    }

    override fun getEditProfileUrl(): String {
        return myPluginDescriptor.getPluginResourcesPath("settings.html")
    }

    override fun getInitialParameterValues(): Map<String, String> {
        return mapOf(AzureConstants.CREDENTIALS_TYPE to AzureConstants.CREDENTIALS_MSI)
    }

    override fun getPropertiesProcessor(): PropertiesProcessor {
        return PropertiesProcessor { properties ->
            properties.keys
                    .filter { SKIP_PARAMETERS.contains(it) }
                    .forEach { properties.remove(it) }

            emptyList()
        }
    }

    override fun canBeAgentOfType(description: AgentDescription): Boolean {
        val availableParameters = description.availableParameters
        return availableParameters.containsKey(AzureProperties.INSTANCE_NAME)
                || availableParameters.containsKey("env." + AzureProperties.INSTANCE_ENV_VAR)
    }

    companion object {
        private val SKIP_PARAMETERS = listOf(
                AzureConstants.IMAGE_URL, AzureConstants.OS_TYPE,
                AzureConstants.MAX_INSTANCES_COUNT, AzureConstants.MAX_INSTANCES_COUNT,
                AzureConstants.VM_USERNAME, AzureConstants.VM_PASSWORD,
                CloudImageParameters.SOURCE_ID_FIELD)
    }
}
