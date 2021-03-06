// Copyright (c) Microsoft. All rights reserved.

package com.microsoft.azure.iotsolutions.iothubmanager.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.microsoft.azure.iotsolutions.iothubmanager.services.exceptions.*;
import com.microsoft.azure.iotsolutions.iothubmanager.services.external.IStorageAdapterClient;
import com.microsoft.azure.iotsolutions.iothubmanager.services.models.*;
import com.microsoft.azure.sdk.iot.service.devicetwin.Query;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import com.microsoft.azure.sdk.iot.service.jobs.JobClient;
import com.microsoft.azure.sdk.iot.service.jobs.JobResult;
import play.Logger;
import play.libs.Json;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class Jobs implements IJobs {

    private static final Logger.ALogger log = Logger.of(Jobs.class);

    private IIoTHubWrapper ioTHubService;
    private final IStorageAdapterClient storageAdapterClient;
    private final JobClient jobClient;
    private final IDeviceProperties deviceProperties;

    private final String DEVICE_DETAILS_QUERY_FORMAT = "select * from devices.jobs where devices.jobs.jobId = '%s'";
    private final String DEVICE_DETAILS_QUERYWITH_STATUS_FORMAT = "select * from devices.jobs where devices.jobs.jobId = '%s' and devices.jobs.status = '%s'";

    @Inject
    public Jobs(final IIoTHubWrapper ioTHubService,
                final IStorageAdapterClient storageAdapterClient,
                IDeviceProperties deviceProperties) throws Exception {
        this.deviceProperties = deviceProperties;
        this.ioTHubService = ioTHubService;
        this.storageAdapterClient = storageAdapterClient;
        this.jobClient = ioTHubService.getJobClient();
    }

    @Override
    public CompletionStage<List<JobServiceModel>> getJobsAsync(
        JobType jobType,
        JobStatus jobStatus,
        Integer pageSize,
        long from, long to)
        throws InvalidInputException, ExternalDependencyException {
        try {
            Query query = this.jobClient.queryJobResponse(
                jobType == null ? null : JobType.toAzureJobType(jobType),
                jobStatus == null ? null : JobStatus.toAzureJobStatus(jobStatus),
                pageSize);

            List jobs = new ArrayList<JobResult>();
            while (this.jobClient.hasNextJob(query)) {
                JobResult job = this.jobClient.getNextJob(query);
                if (job.getCreatedTime().getTime() >= from && job.getCreatedTime().getTime() <= to) {
                    jobs.add(new JobServiceModel(job, null));
                }
            }
            return CompletableFuture.supplyAsync(() -> jobs);
        } catch (IOException | IotHubException e) {
            String message = String.format("Unable to query device jobs by: %s, %s, %d", jobType, jobStatus, pageSize);
            log.error(message, e);
            throw new ExternalDependencyException(message, e);
        }
    }

    @Override
    public CompletionStage<JobServiceModel> getJobAsync(
        String jobId,
        boolean includeDeviceDetails,
        DeviceJobStatus devicejobStatus)
        throws ExternalDependencyException {
        try {
            JobResult result = this.jobClient.getJob(jobId);
            JobServiceModel jobModel;
            if (!includeDeviceDetails) {
                jobModel = new JobServiceModel(result, null);
            } else {
                String queryString = devicejobStatus == null ? String.format(DEVICE_DETAILS_QUERY_FORMAT, jobId) :
                    String.format(DEVICE_DETAILS_QUERYWITH_STATUS_FORMAT, jobId, devicejobStatus);
                Query query = this.jobClient.queryDeviceJob(queryString);
                List deviceJobs = new ArrayList<JobServiceModel>();
                while (this.jobClient.hasNextJob(query)) {
                    JobResult deviceJob = this.jobClient.getNextJob(query);
                    deviceJobs.add(deviceJob);
                }
                jobModel = new JobServiceModel(result, deviceJobs);
            }
            return CompletableFuture.supplyAsync(() -> jobModel);
        } catch (IOException | IotHubException e) {
            String message = String.format("Unable to get device job by id: %s", jobId);
            log.error(message, e);
            throw new ExternalDependencyException(message, e);
        }
    }

    @Override
    public CompletionStage<JobServiceModel> scheduleDeviceMethodAsync(
        String jobId,
        String queryCondition,
        MethodParameterServiceModel parameter,
        Date startTime,
        long maxExecutionTimeInSeconds)
        throws ExternalDependencyException, InvalidInputException {
        // The json payload needs to be passed in the form of HashMap otherwise java will double escape it.
        Map<String, Object> mapPayload = new Hashtable<String, Object>();
        ObjectMapper mapper = new ObjectMapper();
        if (parameter.getJsonPayload() != "") {
            try {
                //convert JSON string to Map
                mapPayload = mapper.readValue(parameter.getJsonPayload(), new TypeReference<HashMap<String, String>>() {
                });
            } catch (Exception e) {
                String message = String.format("Unable to parse cloudToDeviceMethod: %s",
                        parameter.getJsonPayload());
                log.error(message, e);
                throw new InvalidInputException(message, e);
            }
        }
        try {
            JobResult result = this.jobClient.scheduleDeviceMethod(
                jobId,
                queryCondition,
                parameter.getName(),
                parameter.getResponseTimeout() == null ? null : parameter.getResponseTimeout().getSeconds(),
                parameter.getConnectionTimeout() == null ? null : parameter.getConnectionTimeout().getSeconds(),
                mapPayload,
                startTime,
                maxExecutionTimeInSeconds);
            JobServiceModel jobModel = new JobServiceModel(result, null);
            return CompletableFuture.supplyAsync(() -> jobModel);
        } catch (IOException | IotHubException e) {
            String message = String.format("Unable to schedule device method job: %s, %s, %s",
                jobId, queryCondition, Json.stringify(Json.toJson(parameter)));
            log.error(message, e);
            throw new ExternalDependencyException(message, e);
        }
    }

    @Override
    public CompletionStage<JobServiceModel> scheduleTwinUpdateAsync(
        String jobId,
        String queryCondition,
        TwinServiceModel twin,
        Date startTime,
        long maxExecutionTimeInSeconds)
        throws ExternalDependencyException {
        try {
            DevicePropertyServiceModel model = new DevicePropertyServiceModel();
            if (twin.getTags() != null) {
                model.setTags(new HashSet<String>(twin.getTags().keySet()));
            }
            if (twin.getProperties() != null && twin.getProperties().getReported() != null) {
                model.setReported(new HashSet<String>(twin.getProperties().getReported().keySet()));
            }
            // Update the deviceProperties cache, no need to wait
            CompletionStage unused = this.deviceProperties.updateListAsync(model);

            JobResult result = this.jobClient.scheduleUpdateTwin(
                jobId,
                queryCondition,
                twin.toDeviceTwinDevice(),
                startTime,
                maxExecutionTimeInSeconds);
            JobServiceModel jobModel = new JobServiceModel(result, null);
            return CompletableFuture.supplyAsync(() -> jobModel);
        } catch (IOException | IotHubException e) {
            String message = String.format("Unable to schedule twin update job: %s, %s, %s",
                jobId, queryCondition, Json.stringify(Json.toJson(twin)));
            log.error(message, e);
            throw new ExternalDependencyException(message, e);
        } catch (InterruptedException e) {
            String message = String.format("Unable to update cache");
            throw new CompletionException(new Exception(message, e));
        }
    }
}
