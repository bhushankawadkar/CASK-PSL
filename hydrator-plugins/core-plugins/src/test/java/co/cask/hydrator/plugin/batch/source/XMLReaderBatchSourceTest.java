/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.hydrator.plugin.batch.source;


import co.cask.cdap.api.artifact.ArtifactVersion;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.etl.api.batch.BatchSource;
import co.cask.cdap.etl.batch.ETLBatchApplication;
import co.cask.cdap.etl.batch.mapreduce.ETLMapReduce;
import co.cask.cdap.etl.mock.batch.MockSink;
import co.cask.cdap.etl.mock.test.HydratorTestBase;
import co.cask.cdap.etl.proto.v2.ETLBatchConfig;
import co.cask.cdap.etl.proto.v2.ETLPlugin;
import co.cask.cdap.etl.proto.v2.ETLStage;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.ArtifactSummary;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.test.ApplicationManager;
import co.cask.cdap.test.DataSetManager;
import co.cask.cdap.test.MapReduceManager;
import co.cask.cdap.test.TestConfiguration;
import co.cask.hydrator.common.Constants;
import com.google.common.base.Charsets;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.python.google.common.collect.ImmutableMap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Unit test for {@link XMLReaderBatchSource} class.
 */
public class XMLReaderBatchSourceTest extends HydratorTestBase {

  private static final ArtifactVersion CURRENT_VERSION = new ArtifactVersion("3.4.0-SNAPSHOT");
  private static final ArtifactId BATCH_APP_ARTIFACT_ID =
    NamespaceId.DEFAULT.artifact("etlbatch", CURRENT_VERSION.getVersion());
  private static final ArtifactSummary ETLBATCH_ARTIFACT =
    new ArtifactSummary(BATCH_APP_ARTIFACT_ID.getArtifact(), BATCH_APP_ARTIFACT_ID.getVersion());
  private static final String SOURCE_FOLDER_PATH = "src/test/resources/xmlsource/";
  private static final String TARGET_FOLDER_PATH = "src/test/resources/xmltarget/";

  @ClassRule
  public static final TestConfiguration CONFIG = new TestConfiguration("explore.enabled", false);

  @BeforeClass
  public static void setupTest() throws Exception {
    setupBatchArtifacts(BATCH_APP_ARTIFACT_ID, ETLBatchApplication.class);
    addPluginArtifact(NamespaceId.DEFAULT.artifact("core-plugins", "1.0.1"), BATCH_APP_ARTIFACT_ID,
                      XMLReaderBatchSource.class);
  }

  /**
   * Method to copy test xml files into source folder path, from where XMLReader read the file.
   */
  private void copyFiles() {
    try {
      String xmlSourceFolder = "src/test/resources/";
      String catalogLargeFile = "catalogLarge.xml";
      String catalogSmallFile = "catalogSmall.xml";
      FileUtils.copyFile(new File(xmlSourceFolder + catalogLargeFile), new File(SOURCE_FOLDER_PATH + catalogLargeFile));
      FileUtils.copyFile(new File(xmlSourceFolder + catalogSmallFile), new File(SOURCE_FOLDER_PATH + catalogSmallFile));
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Method to clear source and target folders. This ensures that source and target folder are ready to use for next
   * JUnit Test case.
   */
  private void clearSourceAndTargetFolder() {
      File sourceDirectory = new File(SOURCE_FOLDER_PATH);
      for (File sourceFile : sourceDirectory.listFiles()) {
        sourceFile.delete();
      }

      File targetDirectory = new File(TARGET_FOLDER_PATH);
      for (File targetFile : targetDirectory.listFiles()) {
        targetFile.delete();
      }
  }

  /**
   * Method to Pre-Populate File tracking KeyValue with previously processed XML file.
   * @param processedFileTable
   * @param preProcessedDate
   * @return
   * @throws Exception
   */
  private Date createPreProcessedRecord(String processedFileTable, Date preProcessedDate) throws Exception {
    DataSetManager<KeyValueTable> dataSetManager1 = getDataset(processedFileTable);
    KeyValueTable keyValueTable = dataSetManager1.get();
    //put one record of processed file.
    File catalogLarge = new File(SOURCE_FOLDER_PATH + "catalogLarge.xml");
    keyValueTable.write(catalogLarge.toURI().toString().getBytes(Charsets.UTF_8),
                        String.valueOf(preProcessedDate.getTime()).getBytes(Charsets.UTF_8));
    dataSetManager1.flush();
    return preProcessedDate;
  }

  /**
   * Method to return currently processed XML file list.
   * @param processedFileTable
   * @param preProcessedDate
   * @return
   * @throws Exception
   */
  private List<String> getProcessedFileList(String processedFileTable, Date preProcessedDate) throws Exception {
    DataSetManager<KeyValueTable> dataSetManager = getDataset(processedFileTable);
    KeyValueTable table = dataSetManager.get();
    CloseableIterator<KeyValue<byte[], byte[]>> iterator = table.scan(null, null);

    List<String> processedFileList  = new ArrayList<String>();

    while (iterator.hasNext()) {
      KeyValue<byte[], byte[]> keyValue = iterator.next();
      Date date = new Date(Long.valueOf(new String(keyValue.getValue(), Charsets.UTF_8)));
      if (date.after(preProcessedDate)) {
        processedFileList.add(new String(keyValue.getKey(), Charsets.UTF_8));
      }
    }
    return processedFileList;
  }

  @Test
  /**
   * This test validate following
   * 1. Read multiple files from folder
   * 2. Delete files once processed
   * 3. Filter Pre-Processed file
   */
  public void testXMLReaderWithNoXMLPreProcessingRequired() throws Exception {
    copyFiles();
    String processedFileTable = "XMLTrackingTableNoXMLPreProcessingRequired";
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "XMLReaderNoXMLPreProcessingRequiredTest")
      .put("path", SOURCE_FOLDER_PATH)
      .put("nodePath", "/catalog/book/price")
      .put("targetFolder", TARGET_FOLDER_PATH)
      .put("reprocessingReq", "No")
      .put("tableName", processedFileTable)
      .put("actionAfterProcess", "Delete")
      .build();

    ETLStage source = new ETLStage("XMLReader", new ETLPlugin("XMLReader", BatchSource.PLUGIN_TYPE,
                                                              sourceProperties, null));

    String outputDatasetName = "output-batchsink-test-no-preprocessing-required";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "XMLReaderNoXMLPreProcessingRequiredTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    Date preProcessedDate = new Date();
    createPreProcessedRecord(processedFileTable, preProcessedDate);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);

    DataSetManager<KeyValueTable> dataSetManager = getDataset(processedFileTable);
    KeyValueTable table = dataSetManager.get();
    CloseableIterator<KeyValue<byte[], byte[]>> iterator = table.scan(null, null);

    //assert for number of files processed
    List<String> processedFileList = getProcessedFileList(processedFileTable, preProcessedDate);
    Assert.assertEquals(1, processedFileList.size());

    //assert for number of record derived.
    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);
    Assert.assertEquals(3, output.size());

    //source folder left with one pre-processed file
    File sourceFolder = new File(SOURCE_FOLDER_PATH);
    File[] sourceFiles = sourceFolder.listFiles();
    Assert.assertEquals(1, sourceFiles.length);

    clearSourceAndTargetFolder();
  }

  @Test
  /**
   * This test validate following
   * 1. Read multiple files from folder
   * 2. PreProcessing of files
   */
  public void testXMLReaderWithXMLPreProcessingRequired() throws Exception {
    copyFiles();
    String processedFileTable = "XMLTrackingTableXMLPreProcessingRequired";

    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "XMLReaderXMLPreProcessingRequiredTest")
      .put("path", SOURCE_FOLDER_PATH)
      .put("nodePath", "/catalog/book/price")
      .put("targetFolder", TARGET_FOLDER_PATH)
      .put("reprocessingReq", "Yes")
      .put("tableName", processedFileTable)
      .put("actionAfterProcess", "")
      .build();

    ETLStage source = new ETLStage("XMLReader", new ETLPlugin("XMLReader", BatchSource.PLUGIN_TYPE,
                                                              sourceProperties, null));

    String outputDatasetName = "output-batchsink-test-preprocessing-required";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "XMLReaderXMLPreProcessingRequiredTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    Date preProcessedDate = new Date();
    createPreProcessedRecord(processedFileTable, preProcessedDate);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);

    List<String> processedFileList = getProcessedFileList(processedFileTable, preProcessedDate);
    Assert.assertEquals(2, processedFileList.size());

    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);
    Assert.assertEquals(12, output.size());

    clearSourceAndTargetFolder();
  }

  @Test
  public void testXMLReaderWithInvalidNodePathArchiveFiles() throws Exception {
    copyFiles();
    String processedFileTable = "XMLTrackingTableInvalidNodePathArchiveFiles";
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "XMLReaderInvalidNodePathArchiveFilesTest")
      .put("path", SOURCE_FOLDER_PATH)
      .put("nodePath", "/catalog/book/prices") //invalid node path - prices instead of price
      .put("targetFolder", TARGET_FOLDER_PATH)
      .put("reprocessingReq", "No")
      .put("tableName", processedFileTable)
      .put("actionAfterProcess", "archive")
      .build();

    ETLStage source = new ETLStage("XMLReader", new ETLPlugin("XMLReader", BatchSource.PLUGIN_TYPE,
                                                              sourceProperties, null));

    String outputDatasetName = "output-batchsink-test-invalid-node-path-archived-files";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "XMLReaderInvalidNodePathArchiveFilesTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);

    //zero records for invalid node path
    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);
    Assert.assertEquals(0, output.size());

    //source folder must have 0 files after archive
    File sourceFolder = new File(SOURCE_FOLDER_PATH);
    File[] sourceFiles = sourceFolder.listFiles();
    Assert.assertEquals(0, sourceFiles.length);

    //target folder must have 2 archived files
    File targetFolder = new File(TARGET_FOLDER_PATH);
    File[] targetFiles = targetFolder.listFiles();
    Assert.assertEquals(2, targetFiles.length);

    clearSourceAndTargetFolder();
  }

  @Test
  public void testXMLReaderWithPatternAndMoveFiles() throws Exception {
    copyFiles();
    String processedFileTable = "XMLTrackingTableWithPatternAndMoveFiles";
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "XMLReaderWithPatternAndMoveFilesTest")
      .put("path", SOURCE_FOLDER_PATH)
      .put("pattern", "Large.xml$") // file ends with Large.xml
      .put("nodePath", "/catalog/book/price")
      .put("targetFolder", TARGET_FOLDER_PATH)
      .put("reprocessingReq", "No")
      .put("tableName", processedFileTable)
      .put("actionAfterProcess", "move")
      .build();

    ETLStage source = new ETLStage("XMLReader", new ETLPlugin("XMLReader", BatchSource.PLUGIN_TYPE,
                                                              sourceProperties, null));

    String outputDatasetName = "output-batchsink-test-pattern-move-files";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "XMLReaderWithPatternAndMoveFilesTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);

    //assert for number of record derived.
    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);
    Assert.assertEquals(9, output.size());

    //source folder must have 1 unprocessed file
    File sourceFolder = new File(SOURCE_FOLDER_PATH);
    File[] sourceFiles = sourceFolder.listFiles();
    Assert.assertEquals(1, sourceFiles.length);

    //target folder must have 1 moved file
    File targetFolder = new File(TARGET_FOLDER_PATH);
    File[] targetFiles = targetFolder.listFiles();
    Assert.assertEquals(1, targetFiles.length);

    clearSourceAndTargetFolder();
  }

  @Test
  public void testXMLReaderWithInvalidPatternDeleteFiles() throws Exception {
    copyFiles();
    String processedFileTable = "XMLTrackingTableInvalidPatternDeleteFiles";
    Map<String, String> sourceProperties = new ImmutableMap.Builder<String, String>()
      .put(Constants.Reference.REFERENCE_NAME, "XMLReaderInvalidPatternDeleteFilesTest")
      .put("path", SOURCE_FOLDER_PATH)
      .put("pattern", "^catalogMedium") //file name start with catalogMedium, does not exist
      .put("nodePath", "/catalog/book/price")
      .put("targetFolder", TARGET_FOLDER_PATH)
      .put("reprocessingReq", "No")
      .put("tableName", processedFileTable)
      .put("actionAfterProcess", "archive")
      .build();

    ETLStage source = new ETLStage("XMLReader", new ETLPlugin("XMLReader", BatchSource.PLUGIN_TYPE,
                                                              sourceProperties, null));

    String outputDatasetName = "output-batchsink-test-invalid-pattern-delete-files";
    ETLStage sink = new ETLStage("sink", MockSink.getPlugin(outputDatasetName));

    ETLBatchConfig etlConfig = ETLBatchConfig.builder("* * * * *")
      .addStage(source)
      .addStage(sink)
      .addConnection(source.getName(), sink.getName())
      .build();

    AppRequest<ETLBatchConfig> appRequest = new AppRequest<>(ETLBATCH_ARTIFACT, etlConfig);
    Id.Application appId = Id.Application.from(Id.Namespace.DEFAULT, "XMLReaderInvalidPatternDeleteFilesTest");
    ApplicationManager appManager = deployApplication(appId, appRequest);

    MapReduceManager mrManager = appManager.getMapReduceManager(ETLMapReduce.NAME);
    mrManager.start();
    mrManager.waitForFinish(5, TimeUnit.MINUTES);

    //0 record fetched as no pattern matching file
    DataSetManager<Table> outputManager = getDataset(outputDatasetName);
    List<StructuredRecord> output = MockSink.readOutput(outputManager);
    Assert.assertEquals(0, output.size());

    //source folder must have 2 files, no file deleted
    File sourceFolder = new File(SOURCE_FOLDER_PATH);
    File[] sourceFiles = sourceFolder.listFiles();
    Assert.assertEquals(2, sourceFiles.length);

    clearSourceAndTargetFolder();
  }

  @Override
  public void afterTest() throws Exception {
    super.afterTest();
    File file = new File("XMLTrackingTableInvalidPatternDeleteFiles");
    file.delete();
  }
}
