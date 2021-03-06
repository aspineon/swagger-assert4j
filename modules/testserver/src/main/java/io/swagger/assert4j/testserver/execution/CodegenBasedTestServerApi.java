package io.swagger.assert4j.testserver.execution;

import com.google.common.collect.Lists;
import io.swagger.assert4j.client.model.CustomProperties;
import io.swagger.assert4j.client.model.DataSource;
import io.swagger.assert4j.client.model.DataSourceTestStep;
import io.swagger.assert4j.client.model.ExcelDataSource;
import io.swagger.assert4j.client.model.FileDataSource;
import io.swagger.assert4j.client.model.HarLogRoot;
import io.swagger.assert4j.client.model.ProjectResultReport;
import io.swagger.assert4j.client.model.ProjectResultReports;
import io.swagger.assert4j.client.model.RequestTestStepBase;
import io.swagger.assert4j.client.model.TestCase;
import io.swagger.assert4j.client.model.TestStep;
import io.swagger.assert4j.TestRecipe;
import io.swagger.assert4j.teststeps.TestSteps;
import com.sun.jersey.api.client.GenericType;
import io.swagger.client.Pair;
import io.swagger.client.auth.HttpBasicAuth;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.swagger.assert4j.teststeps.TestSteps.HttpMethod.POST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

/**
 * TestServerApi implementation that uses a SwaggerCodegen based implementation
 */

public class CodegenBasedTestServerApi implements TestServerApi {

    private static final Logger logger = LoggerFactory.getLogger(CodegenBasedTestServerApi.class);
    private static final String SWAGGER_RESOURCE_PATH = ServerDefaults.SERVICE_BASE_PATH + "/executions/swagger";
    private static final String APPLICATION_JSON = "application/json";
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";

    private ApiClientWrapper apiClient;

    CodegenBasedTestServerApi() {
        this(new ApiClientWrapper());
    }

    public CodegenBasedTestServerApi(ApiClientWrapper apiClientWrapper) {
        apiClient = apiClientWrapper;
    }

    /**
     * Execute submitted test recipe
     *
     * @param testRecipe test recipe to run
     * @param async    if true server will return executionID immediately without waiting for execution to complete.
     *                 If false, it will wait for the execution to finish before it returns executionId and execution results.
     *                 Default is true;
     * @param auth     authentication object
     * @return ProjectResultReport execution result
     */
    @Override
    public ProjectResultReport postTestRecipe(TestRecipe testRecipe, boolean async, HttpBasicAuth auth) throws ApiException {
        // verify the required parameter 'testCase' is set
        if (testRecipe == null) {
            throw new ApiException(400, "Missing the required parameter 'testRecipe' when calling postTestRecipe");
        }
        verifyDataSourceFilesExist(testRecipe.getTestCase());
        setAuthentication(auth);

        // create path and map variables
        String path = (ServerDefaults.SERVICE_BASE_PATH + "/executions").replaceAll("\\{format\\}", "json");

        // query params
        List<Pair> queryParams = new ArrayList<>();
        queryParams.add(new Pair("async", String.valueOf(async)));

        Map<String, File> formParams = new HashMap<>();

        ProjectResultReport projectResultReport = invokeAPI(path, POST.name(), testRecipe.getTestCase(), APPLICATION_JSON, queryParams, formParams);
        return sendPendingFiles(testRecipe.getTestCase(), projectResultReport, queryParams);
    }

    private void verifyDataSourceFilesExist(TestCase testCase) {
        for (TestStep testStep : testCase.getTestSteps()) {
            if (testStep instanceof DataSourceTestStep) {
                DataSource dataSource = ((DataSourceTestStep) testStep).getDataSource();
                if (dataSource.getExcel() != null) {
                    verifyFileExists(dataSource.getExcel().getFile());
                }
                if (dataSource.getFile() != null) {
                    verifyFileExists(dataSource.getFile().getFile());
                }
            }
        }
    }

    @Override
    public void setConnectTimeout(int connectionTimeout) {
        apiClient.setConnectTimeout(connectionTimeout);
    }

    @Override
    public ProjectResultReport addFiles(String executionID, Collection<File> filesToAdd, boolean async) throws io.swagger.client.ApiException {
        List<Pair> queryParameters = Collections.singletonList(new Pair("async", String.valueOf(async)));
        Map<String, File> files = filesToAdd.stream().collect(toMap(File::getName, Function.identity()));
        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions/" + executionID + "/files";
        return apiClient.invokeAPI(path, "POST", queryParameters,
                null, files, APPLICATION_JSON, MULTIPART_FORM_DATA, new String[0], null);
    }

    @Override
    public void setDebugging(boolean debugging) {
        apiClient.setDebugging(debugging);
    }

    private ApiClientWrapper getApiClient() {
        return apiClient;
    }

    private ProjectResultReport sendPendingFiles(TestCase body, ProjectResultReport projectResultReport, List<Pair> queryParams) {
        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions/" + projectResultReport.getExecutionID() + "/files";

        Map<String, File> formParams = buildFormParametersForDataSourceFiles(body);
        addClientCertificateFile(formParams, body.getClientCertFileName());
        addTestStepClientCertificateFile(body, formParams);
        if (formParams.isEmpty()) {
            return projectResultReport;
        }
        return invokeAPI(path, POST.name(), body, "multipart/form-data", queryParams, formParams);
    }

    private void addTestStepClientCertificateFile(TestCase body, Map<String, File> formParams) {
        for (TestStep testStep : body.getTestSteps()) {
            if (testStep instanceof RequestTestStepBase) {
                RequestTestStepBase testStepBase = (RequestTestStepBase) testStep;
                addClientCertificateFile(formParams, testStepBase.getClientCertificateFileName());
            }
        }
    }

    private void addClientCertificateFile(Map<String, File> formParams, String clientCertFileName) {
        if (StringUtils.isNotEmpty(clientCertFileName)) {
            File certificateFile = new File(clientCertFileName);
            if (certificateFile.exists()) {
                formParams.put(certificateFile.getName(), certificateFile);
            } else {
                logger.warn("Client certificate file not found, file path: " + clientCertFileName +
                        ". The TestServer execution will fail unless file exists on TestServer and " +
                        "the file path has been added to allowed file paths.");
            }
        }
    }

    /**
     * Returns last executions
     *
     * @return ProjectResultReports
     */
    @Override
    public ProjectResultReports getExecutions(HttpBasicAuth auth) throws ApiException {
        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions";
        setAuthentication(auth);
        List<Pair> queryParams = new ArrayList<>();
        Map<String, File> formParams = new HashMap<>();

        GenericType returnType = new GenericType<ProjectResultReports>() {
        };
        return (ProjectResultReports) apiClient.invokeAPI(path, TestSteps.HttpMethod.GET.name(), queryParams, null, formParams,
                APPLICATION_JSON, APPLICATION_JSON, getAuthNames(), returnType);

    }

    /**
     * Cancels execution
     *
     * @param executionID execution id
     * @return ProjectResultReport
     */

    @Override
    public ProjectResultReport cancelExecution(String executionID, HttpBasicAuth auth) throws ApiException {
        if (executionID == null) {
            throw new ApiException(400, "Missing the required parameter 'executionID' when calling cancelExecution");
        }
        setAuthentication(auth);
        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions/" + executionID;

        return invokeAPI(path, TestSteps.HttpMethod.DELETE.name(), null, APPLICATION_JSON, new ArrayList<Pair>(),
                new HashMap<String, File>());

    }

    /**
     * Gets transaction log for the provided execution id and transaction id
     *
     * @param executionID   execution id
     * @param transactionId transaction id
     * @return HarLogRoot
     */
    public HarLogRoot getTransactionLog(String executionID, String transactionId, HttpBasicAuth auth) throws ApiException {
        if (executionID == null) {
            throw new ApiException(400, "Missing the required parameter 'executionID' when calling cancelExecution");
        }
        setAuthentication(auth);
        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions/" + executionID + "/transactions/" + transactionId;

        return getTransactionLog(path, TestSteps.HttpMethod.GET.name(), null, APPLICATION_JSON, new ArrayList<Pair>(),
                new HashMap<String, File>());

    }

    private Map<String, File> buildFormParametersForDataSourceFiles(TestCase testCase) {
        Map<String, File> formParams = new HashMap<>();
        for (TestStep testStep : testCase.getTestSteps()) {
            if (testStep instanceof DataSourceTestStep) {
                DataSource dataSource = ((DataSourceTestStep) testStep).getDataSource();
                addDataSourceFile(formParams, dataSource.getExcel());
                addDataSourceFile(formParams, dataSource.getFile());
            }
        }
        return formParams;
    }

    private void addDataSourceFile(Map<String, File> formParams, FileDataSource fileDataSource) {
        if (fileDataSource != null) {
            File dataSourceFile = new File(fileDataSource.getFile());
            formParams.put(dataSourceFile.getName(), dataSourceFile);
        }
    }

    private void addDataSourceFile(Map<String, File> formParams, ExcelDataSource excelDataSource) {
        if (excelDataSource != null) {
            File dataSourceFile = new File(excelDataSource.getFile());
            formParams.put(dataSourceFile.getName(), dataSourceFile);
        }
    }

    private void verifyFileExists(String filePath) {
        if (!new File(filePath).exists()) {
            throw new ApiException(400, "Data source file not found: " + filePath);
        }
    }

    /**
     * Gets execution report
     *
     * @param executionID execution id received when test case was submitted for execution
     * @return ProjectResultReport execution result
     */
    @Override
    public ProjectResultReport getExecutionStatus(String executionID, HttpBasicAuth auth) throws ApiException {
        // verify the required parameter 'executionID' is set
        if (executionID == null) {
            throw new ApiException(400, "Missing the required parameter 'executionID' when calling getExecutionStatus");
        }
        setAuthentication(auth);

        // create path and map variables
        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions/" + executionID + "/status";

        return invokeAPI(path, TestSteps.HttpMethod.GET.name(), null, APPLICATION_JSON, new ArrayList<Pair>());
    }

    private void setAuthentication(HttpBasicAuth auth) {
        if (auth != null) {
            apiClient.setUsername(auth.getUsername());
            apiClient.setPassword(auth.getPassword());
        }
    }

    private ProjectResultReport invokeAPI(String path, String method, Object postBody, String contentType,
                                          List<Pair> queryParams) throws ApiException {
        return invokeAPI(path, method, postBody, contentType, queryParams, new HashMap<String, File>());
    }

    private ProjectResultReport invokeAPI(String path, String method, Object postBody, String contentType,
                                          List<Pair> queryParams, Map<String, File> formParams) throws ApiException {

        return (ProjectResultReport) apiClient.invokeAPI(path, method, queryParams, postBody, formParams, APPLICATION_JSON, contentType,
                getAuthNames(), getReturnTypeProjectResultReport());
    }

    private HarLogRoot getTransactionLog(String path, String method, Object postBody, String contentType,
                                         List<Pair> queryParams, Map<String, File> formParams) throws ApiException {

        return (HarLogRoot) apiClient.invokeAPI(path, method, queryParams, postBody, formParams, APPLICATION_JSON, contentType,
                getAuthNames(), getReturnTypeHarLogRoot());
    }

    private String[] getAuthNames() {
        return new String[]{"basicAuth"};
    }

    private GenericType getReturnTypeProjectResultReport() {
        return new GenericType<ProjectResultReport>() {
        };
    }

    private GenericType getReturnTypeHarLogRoot() {
        return new GenericType<HarLogRoot>() {
        };
    }

    @Override
    public void setBasePath(String basePath) {
        apiClient.setBasePath(basePath);
    }

    @Override
    public ProjectResultReport postSwagger(File swaggerFile, SwaggerApiValidator.SwaggerFormat swaggerFormat,
                                           String endpoint, String callBackUrl, boolean async, HttpBasicAuth auth) throws ApiException {
        if (!swaggerFile.exists()) {
            throw new ApiException(404, "File [" + swaggerFile.toString() + "] not found");
        }
        setAuthentication(auth);
        List<Pair> queryParams = new ArrayList<>();
        queryParams.add(new Pair("async", String.valueOf(false)));
        if (StringUtils.isNotEmpty(endpoint)) {
            queryParams.add(new Pair("endpoint", endpoint));
        }
        if (StringUtils.isNotEmpty(callBackUrl)) {
            queryParams.add(new Pair("callback", callBackUrl));
        }
        try {
            byte[] data = Files.readAllBytes(swaggerFile.toPath());
            return invokeAPI(SWAGGER_RESOURCE_PATH, POST.name(), data, swaggerFormat.getMimeType(), queryParams,
                    null);
        } catch (IOException e) {
            throw new ApiException(500, "Failed to read Swagger file; " + e.toString());
        }
    }

    @Override
    public ProjectResultReport postSwagger(URL swaggerApiURL, String endpoint, String callBackUrl, boolean async, HttpBasicAuth auth)
            throws ApiException {
        if (swaggerApiURL == null) {
            throw new ApiException(404, "Swagger API URL is null.");
        }
        setAuthentication(auth);
        List<Pair> queryParams = new ArrayList<>();
        queryParams.add(new Pair("async", String.valueOf(async)));
        if (StringUtils.isNotEmpty(endpoint)) {
            queryParams.add(new Pair("endpoint", endpoint));
        }
        if (StringUtils.isNotEmpty(callBackUrl)) {
            queryParams.add(new Pair("callback", callBackUrl));
        }
        queryParams.add(new Pair("swaggerEndpoint", swaggerApiURL.toString()));
        return invokeAPI(SWAGGER_RESOURCE_PATH, POST.name(), null, APPLICATION_JSON, queryParams, null);
    }

    @Override
    public ProjectResultReport postProject(ProjectExecutionRequest executionRequest, boolean async, HttpBasicAuth auth)
            throws ApiException {
        File projectFile = executionRequest.getProjectFile();
        if (!projectFile.exists()) {
            throw new ApiException(404, "File [" + projectFile.toString() + "] not found");
        }

        setAuthentication(auth);

        List<Pair> queryParams = buildQueryParameters(executionRequest, async);

        String path = ServerDefaults.SERVICE_BASE_PATH + "/executions";
        String type = "application/xml";

        try {
            // composite project?
            if (projectFile.isDirectory()) {
                projectFile = zipCompositeProject(projectFile);
                path += "/composite";
                type = "application/zip";
            } else {
                path += "/xml";
            }

            if (executionRequest.getCustomPropertiesMap().isEmpty()) {
                byte[] data = Files.readAllBytes(projectFile.toPath());
                return invokeAPI(path, POST.name(), data, type, queryParams, null);
            } else {
                File propertiesFile = writeCustomPropertiesToFile(executionRequest.getCustomPropertiesMap().values());

                Map<String, File> formParams = new HashMap<>();
                formParams.put(projectFile.getName(), projectFile);
                formParams.put(propertiesFile.getName(), propertiesFile);
                return invokeAPI(path, POST.name(), null, "multipart/form-data", queryParams, formParams);
            }

        } catch (IOException e) {
            throw new ApiException(500, "Failed to read project; " + e.toString());
        }
    }

    private File writeCustomPropertiesToFile(Collection<CustomProperties> values) throws ApiException {
        try {
            String content = (String) getApiClient().serialize(values, APPLICATION_JSON);
            File tempFile = File.createTempFile("custom-properties", ".json");
            Files.write(tempFile.toPath(), content.getBytes(UTF_8));
            tempFile.deleteOnExit();
            return tempFile;
        } catch (IOException e) {
            throw new ApiException(400, "Failed to create custom properties file.");
        }
    }

    @Override
    public ProjectResultReport postRepositoryProject(RepositoryProjectExecutionRequest request, boolean async, HttpBasicAuth auth) throws ApiException {
        setAuthentication(auth);
        List<Pair> queryParams = buildQueryParameters(request, async);
        queryParams.add(new Pair("projectFileName", request.getProjectFileName()));
        if (request.getRepositoryName() != null) {
            queryParams.add(new Pair("repositoryName", request.getRepositoryName()));
        }

        return invokeAPI(ServerDefaults.SERVICE_BASE_PATH + "/executions/project", POST.name(), request.getCustomPropertiesMap().values(),
                APPLICATION_JSON, queryParams, null);
    }

    private List<Pair> buildQueryParameters(ProjectExecutionRequestBase executionRequest, boolean async) {
        List<Pair> queryParams = new ArrayList<>();
        queryParams.add(new Pair("async", String.valueOf(async)));
        if (executionRequest.getTestCaseName() != null) {
            queryParams.add(new Pair("testCaseName", executionRequest.getTestCaseName()));
        }
        if (executionRequest.getTestSuiteName() != null) {
            queryParams.add(new Pair("testSuiteName", executionRequest.getTestSuiteName()));
        }
        if (executionRequest.getEnvironment() != null) {
            queryParams.add(new Pair("environment", executionRequest.getEnvironment()));
        }
        if (executionRequest.getEndpoint() != null) {
            queryParams.add(new Pair("hostAndPort", executionRequest.getEndpoint()));
        }
        if (executionRequest.getProjectPassword() != null) {
            queryParams.add(new Pair("projectPassword", executionRequest.getProjectPassword()));
        }
        if (!executionRequest.getTags().isEmpty()) {
            queryParams.add(new Pair("tags", String.join(",", executionRequest.getTags())));
        }
        return queryParams;
    }

    private File zipCompositeProject(File dir) throws IOException {
        File zipFile = File.createTempFile("soapui-project", ".zip");
        zipFile.deleteOnExit();

        byte[] buffer = new byte[1024];

        try (
                FileOutputStream fout = new FileOutputStream(zipFile);
                ZipOutputStream zout = new ZipOutputStream(fout)) {

            List<String> files = Lists.newArrayList();
            populateFilesList(dir, files);

            for (String fileName : files) {
                File file = new File(fileName);

                try (FileInputStream fin = new FileInputStream(file)) {
                    String zipEntryName = fileName.substring(dir.getAbsolutePath().length());
                    zout.putNextEntry(new ZipEntry(zipEntryName));

                    int length;

                    while ((length = fin.read(buffer)) > 0) {
                        zout.write(buffer, 0, length);
                    }

                    zout.closeEntry();
                }
            }
        }

        return zipFile;
    }

    private void populateFilesList(File dir, List<String> files) throws IOException {
        File[] filesInDir = dir.listFiles();
        if (filesInDir == null || filesInDir.length == 0) {
            return;
        }
        for (File file : filesInDir) {
            if (file.isFile()) {
                files.add(file.getAbsolutePath());
            } else {
                populateFilesList(file, files);
            }
        }
    }
}
