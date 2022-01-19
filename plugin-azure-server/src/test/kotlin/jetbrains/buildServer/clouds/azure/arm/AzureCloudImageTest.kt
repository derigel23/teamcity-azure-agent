/*
 * Copyright 2000-2022 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.gson.annotations.SerializedName
import io.mockk.*
import jetbrains.buildServer.clouds.CloudImageParameters
import jetbrains.buildServer.clouds.CloudInstanceUserData
import jetbrains.buildServer.clouds.InstanceStatus
import jetbrains.buildServer.clouds.azure.arm.*
import jetbrains.buildServer.clouds.azure.arm.connector.AzureApiConnector
import jetbrains.buildServer.clouds.azure.arm.connector.AzureInstance
import kotlinx.coroutines.runBlocking
import org.jmock.MockObjectTestCase
import org.testng.annotations.BeforeMethod
import org.testng.annotations.BeforeTest
import org.testng.annotations.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

class AzureCloudImageTest : MockObjectTestCase() {
    private lateinit var myImageDetails: AzureCloudImageDetails
    private lateinit var myApiConnector: AzureApiConnector

    @BeforeMethod
    fun beforeMethod() {
        MockKAnnotations.init(this)

        myApiConnector = mockk();
        coEvery { myApiConnector.createInstance(any(), any()) } returns Unit
        every { myApiConnector.fetchInstances<AzureInstance>(any<AzureCloudImage>()) } returns emptyMap()

        myImageDetails = AzureCloudImageDetails(
            mySourceId = null,
            deployTarget = AzureCloudDeployTarget.SpecificGroup,
            regionId = "regionId",
            groupId = "groupId",
            imageType = AzureCloudImageType.Image,
            imageUrl = null,
            imageId = "imageId",
            instanceId = null,
            osType = null,
            networkId = null,
            subnetId = null,
            vmNamePrefix = "vm",
            vmSize = null,
            vmPublicIp = null,
            myMaxInstances = 2,
            username = null,
            storageAccountType = null,
            template = null,
            numberCores = null,
            memory = null,
            storageAccount = null,
            registryUsername = null,
            agentPoolId = null,
            profileId = null,
            myReuseVm = false,
            customEnvironmentVariables = null,
            spotVm = null,
            enableSpotPrice = null,
            spotPrice = null,
            enableAcceleratedNetworking = null
        )
    }

    @Test
    fun shouldCreateNewInstance() {
        // Given
        val instance = createInstance()
        val userData = CloudInstanceUserData(
                "agentName",
                "authToken",
                "",
                0,
                "profileId",
                "profileDescr",
                emptyMap()
        )

        // When
        runBlocking {
            instance.startNewInstance(userData)
        }

        // Then
        coVerify { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                    i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "1" &&
                    i.image == instance &&
                    i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "1" &&
                    userData.profileId == "profileId" &&
                    userData.profileDescription == "profileDescr"
                })
        }
    }

    @Test
    fun shouldCreateSecondInstance() {
        // Given
        val instance = createInstance()
        val userData = CloudInstanceUserData(
                "agentName",
                "authToken",
                "",
                0,
                "profileId",
                "profileDescr",
                emptyMap()
        )
        runBlocking {
            instance.startNewInstance(userData)
        }

        // When
        runBlocking {
            instance.startNewInstance(userData)
        }

        // Then
        coVerify { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                            i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "2" &&
                            i.image == instance &&
                            i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "2" &&
                            userData.profileId == "profileId" &&
                            userData.profileDescription == "profileDescr"
                })
        }
    }

    @Test(invocationCount = 30)
    fun shouldCreateSecondInstanceInParallel() {
        // Given
        val instance = createInstance()
        val userData = CloudInstanceUserData(
                "agentName",
                "authToken",
                "",
                0,
                "profileId",
                "profileDescr",
                emptyMap()
        )
        val barrier = CyclicBarrier(3)

        // When
        val thread1 = thread(start = true) { barrier.await(); runBlocking { instance.startNewInstance(userData) } }
        val thread2 = thread(start = true) { barrier.await(); runBlocking { instance.startNewInstance(userData) } }

        barrier.await()

        thread1.join()
        thread2.join()

        // Then
        coVerify { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                            i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "1" &&
                            i.image == instance &&
                            i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "1" &&
                            userData.profileId == "profileId" &&
                            userData.profileDescription == "profileDescr"
                })
        }

        coVerify { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                            i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "2" &&
                            i.image == instance &&
                            i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "2" &&
                            userData.profileId == "profileId" &&
                            userData.profileDescription == "profileDescr"
                })
        }
    }

    @Test(invocationCount = 30)
    fun shouldCheckInstanceLimitWhenCreateInstanceInParallel() {
        // Given
        val instance = createInstance()
        val userData = CloudInstanceUserData(
                "agentName",
                "authToken",
                "",
                0,
                "profileId",
                "profileDescr",
                emptyMap()
        )
        val barrier = CyclicBarrier(4)

        // When
        val thread1 = thread(start = true) { barrier.await(); runBlocking { instance.startNewInstance(userData) } }
        val thread2 = thread(start = true) { barrier.await(); runBlocking { instance.startNewInstance(userData) } }
        val thread3 = thread(start = true) { barrier.await(); runBlocking { instance.startNewInstance(userData) } }

        barrier.await()

        thread1.join()
        thread2.join()
        thread3.join()

        // Then
        coVerify { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                            i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "1" &&
                            i.image == instance &&
                            i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "1" &&
                            userData.profileId == "profileId" &&
                            userData.profileDescription == "profileDescr"
                })
        }

        coVerify { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                            i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "2" &&
                            i.image == instance &&
                            i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "2" &&
                            userData.profileId == "profileId" &&
                            userData.profileDescription == "profileDescr"
                })
        }

        coVerify(exactly = 0) { myApiConnector.createInstance(
                match { i ->
                    i.imageId == instance.id &&
                            i.name == myImageDetails.vmNamePrefix!!.toLowerCase() + "3" &&
                            i.image == instance &&
                            i.status == InstanceStatus.STARTING
                },
                match { userData ->
                    userData.agentName == myImageDetails.vmNamePrefix!!.toLowerCase() + "3" &&
                            userData.profileId == "profileId" &&
                            userData.profileDescription == "profileDescr"
                })
        }
    }

    private fun createInstance() : AzureCloudImage {
        return AzureCloudImage(myImageDetails, myApiConnector)
    }
}
