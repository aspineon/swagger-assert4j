package io.swagger.assert4j.dsl.pro

import io.swagger.assert4j.TestRecipeBuilder
import io.swagger.assert4j.dsl.DslDelegate
import io.swagger.assert4j.testserver.teststeps.datasource.GridDataSourceTestStepBuilder

/**
 * The delegate responding to commands inside the "recipe" closure of ServerTestDsl.
 */
class ProDslDelegate extends DslDelegate {

    ProDslDelegate(TestRecipeBuilder testRecipeBuilder) {
        super(testRecipeBuilder)
    }

    void usingExcelFile(String excelFilePath, String testStepName = 'ExcelDataSource',
                        @DelegatesTo(ExcelDataSourceTestStepDelegate) Closure dataSourceConfig) {
        addDataSourceTestStep(new ExcelDataSourceTestStepDelegate(excelFilePath, testStepName), dataSourceConfig)
    }

    void usingCsvFile(String csvFilePath, String testStepName = 'CsvFileDataSource',
                      @DelegatesTo(FileDataSourceTestStepDelegate) Closure dataSourceConfig) {
        addDataSourceTestStep(new FileDataSourceTestStepDelegate(csvFilePath, testStepName), dataSourceConfig)
    }

    void usingData(Map<String, List<String>> data, String testStepName = 'GridDataSource') {
        GridDataSourceTestStepBuilder gridDataSourceTestStepBuilder = new GridDataSourceTestStepBuilder()
        gridDataSourceTestStepBuilder.named(testStepName)
        data.each { gridDataSourceTestStepBuilder.addProperty(it.key, it.value) }
        testRecipeBuilder.addStep(gridDataSourceTestStepBuilder)
    }

    void withGeneratedData(String testStepName = 'DataGenDataSource',
                           @DelegatesTo(DataGenDataSourceTestStepDelegate) Closure dataSourceConfig) {
        addDataSourceTestStep(new DataGenDataSourceTestStepDelegate(testStepName), dataSourceConfig)
    }

    private void addDataSourceTestStep(DataSourceTestStepDelegate delegate, Closure dataSourceConfig) {
        dataSourceConfig.delegate = delegate
        dataSourceConfig.resolveStrategy = Closure.DELEGATE_FIRST
        dataSourceConfig.call()

        testRecipeBuilder.addStep(delegate.dataSourceTestStepBuilder)
    }
}
