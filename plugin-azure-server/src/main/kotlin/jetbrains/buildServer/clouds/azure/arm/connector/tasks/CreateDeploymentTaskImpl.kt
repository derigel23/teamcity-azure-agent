package jetbrains.buildServer.clouds.azure.arm.connector.tasks

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.microsoft.azure.CloudException
import com.microsoft.azure.management.resources.Deployment
import com.microsoft.azure.management.resources.DeploymentMode
import com.microsoft.azure.management.resources.DeploymentProperties
import com.microsoft.azure.management.resources.fluentcore.arm.ResourceUtils
import com.microsoft.azure.management.resources.fluentcore.utils.Utils
import com.microsoft.azure.management.resources.implementation.DeploymentExtendedInner
import com.microsoft.azure.management.resources.implementation.DeploymentInner
import jetbrains.buildServer.clouds.azure.arm.throttler.AzureTaskContext
import jetbrains.buildServer.clouds.azure.arm.throttler.AzureTaskNotifications
import jetbrains.buildServer.clouds.azure.arm.throttler.AzureThrottlerTaskBaseImpl
import jetbrains.buildServer.clouds.azure.arm.throttler.TEAMCITY_CLOUDS_AZURE_TASKS_CTREATEDEPLOYMENT_USE_MILTITHREAD_POLLING
import jetbrains.buildServer.serverSide.TeamCityProperties
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import rx.Observable
import rx.Single

data class CreateDeploymentTaskParameter(
        val groupName: String,
        val deploymentName: String,
        val template: String,
        val params: String,
        val tags: Map<String, String>,
        val targetResourceType: String)

data class CreateDeploymentTaskDescriptor(
        val instance: FetchInstancesTaskInstanceDescriptor?
)

class CreateDeploymentTaskImpl(private val myNotifications: AzureTaskNotifications) : AzureThrottlerTaskBaseImpl<AzureApi, CreateDeploymentTaskParameter, CreateDeploymentTaskDescriptor>() {
    private val objectMapper = ObjectMapper()

    override fun create(api: AzureApi, taskContext: AzureTaskContext, parameter: CreateDeploymentTaskParameter): Single<CreateDeploymentTaskDescriptor> {
        if (TeamCityProperties.getBooleanOrTrue(TEAMCITY_CLOUDS_AZURE_TASKS_CTREATEDEPLOYMENT_USE_MILTITHREAD_POLLING)) {
            val managerClient = api.deploymentsClient()

            LOG.debug("Starting deployment. Name: ${parameter.deploymentName}, corellationId: [${taskContext.corellationId}]")

            val service = managerClient.azureClient.retrofit().create(DeploymentService::class.java)
            return managerClient
                .azureClient
                .putOrPatchAsync<DeploymentExtendedInner>(service.createOrUpdate(
                    parameter.groupName,
                    parameter.deploymentName,
                    api.subscriptionId(),
                    getDeploymentInner(parameter),
                    managerClient.apiVersion(),
                    managerClient.acceptLanguage(),
                    managerClient.userAgent()
                )) {
                    taskContext.getDeferralSequence()
                }
                .concatMap {
                    val event = AzureTaskDeploymentStatusChangedEventArgs(
                            api,
                            it.id(),
                            it.name(),
                            it.properties().provisioningState(),
                            it.properties().providers(),
                            it.properties().dependencies(),
                            taskContext,
                    )
                    myNotifications
                            .raise(event)
                            .map { CreateDeploymentTaskDescriptor(event.instance) }
                }
                .onErrorResumeNext { throwable ->
                    if (throwable is CloudException &&
                        DEPLOYMENT_NOT_FOUND_CODE.equals(throwable.body()?.code(), ignoreCase = true) &&
                        VIRTUAL_MACHINES_RESOURCE_TYPE.equals(parameter.targetResourceType, ignoreCase = true)
                    ) {
                        LOG.debug("Deployment has gone. Name: ${parameter.deploymentName}, corellationId: [${taskContext.corellationId}]")
                        val event = AzureTaskVirtualMachineCreated(
                            api,
                            taskContext,
                            ResourceUtils.constructResourceId(
                                api.subscriptionId(),
                                parameter.groupName,
                                VIRTUAL_MACHINES_PROVIDER_NAMESPACE,
                                VIRTUAL_MACHINES_RESOURCE_TYPE_SHORT,
                                parameter.deploymentName,
                                ""),
                        )
                        myNotifications
                            .raise(event)
                            .map { CreateDeploymentTaskDescriptor(event.instance) }
                    } else {
                        Observable.error(throwable)
                    }
                }
                .toSingle()

        } else {
            return Utils.rootResource<Deployment>(
                api
                    .deployments()
                    .define(parameter.deploymentName)
                    .withExistingResourceGroup(parameter.groupName)
                    .withTemplate(parameter.template)
                    .withParameters(parameter.params)
                    .withMode(DeploymentMode.INCREMENTAL)
                    .createAsync()
            )
                .concatMap {
                    val inner = it.inner()
                    val event = AzureTaskDeploymentStatusChangedEventArgs(
                        api,
                        inner.id(),
                        inner.name(),
                        inner.properties().provisioningState(),
                        inner.properties().providers(),
                        inner.properties().dependencies(),
                        taskContext
                    )
                    myNotifications
                        .raise(event)
                        .map { CreateDeploymentTaskDescriptor(event.instance) }
                }
                .toSingle()
        }
    }

    private fun getDeploymentInner(parameter: CreateDeploymentTaskParameter): DeploymentInner {
        val deploymentProperties = DeploymentProperties()
        deploymentProperties
            .withTemplate(objectMapper.readValue(parameter.template, Any::class.java))
            .withParameters(objectMapper.readValue(parameter.params, Any::class.java))
            .withMode(DeploymentMode.INCREMENTAL)

        val deploymentInner = DeploymentInner()
        deploymentInner.withProperties(deploymentProperties)
        deploymentInner.withTags(parameter.tags)

        return deploymentInner
    }

    interface DeploymentService {
        @Headers("Content-Type: application/json; charset=utf-8")
        @PUT("subscriptions/{subscriptionId}/resourcegroups/{resourceGroupName}/providers/Microsoft.Resources/deployments/{deploymentName}")
        fun createOrUpdate(
            @Path("resourceGroupName") resourceGroupName: String,
            @Path("deploymentName") deploymentName: String,
            @Path("subscriptionId") subscriptionId: String?,
            @Body parameters: DeploymentInner,
            @Query("api-version") apiVersion: String,
            @Header("accept-language") acceptLanguage: String,
            @Header("User-Agent") userAgent: String
        ): Observable<Response<ResponseBody>>
    }

    companion object {
        private val LOG = Logger.getInstance(CreateDeploymentTaskImpl::class.java.name)
        private const val VIRTUAL_MACHINES_PROVIDER_NAMESPACE = "Microsoft.Compute"
        private const val VIRTUAL_MACHINES_RESOURCE_TYPE_SHORT = "virtualMachines"
        private const val VIRTUAL_MACHINES_RESOURCE_TYPE = "Microsoft.Compute/virtualMachines"
        private const val DEPLOYMENT_NOT_FOUND_CODE = "DeploymentNotFound"
    }
}
