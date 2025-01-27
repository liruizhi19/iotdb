/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb;

import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.engine.modification.Deletion;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.utils.QueryUtils;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.Session;
import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.exception.write.NoMeasurementException;
import org.apache.iotdb.tsfile.file.MetaMarker;
import org.apache.iotdb.tsfile.file.header.ChunkGroupHeader;
import org.apache.iotdb.tsfile.file.header.ChunkHeader;
import org.apache.iotdb.tsfile.file.metadata.AlignedChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.IChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Field;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.controller.CachedChunkLoaderImpl;
import org.apache.iotdb.tsfile.read.controller.IChunkLoader;
import org.apache.iotdb.tsfile.read.controller.IMetadataQuerier;
import org.apache.iotdb.tsfile.read.controller.MetadataQuerierByFileImpl;
import org.apache.iotdb.tsfile.read.query.dataset.DataSetWithoutTimeGenerator;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.read.reader.series.AbstractFileSeriesReader;
import org.apache.iotdb.tsfile.read.reader.series.EmptyFileSeriesReader;
import org.apache.iotdb.tsfile.read.reader.series.FileSeriesReader;
import org.apache.iotdb.tsfile.utils.FilePathUtils;
import org.apache.iotdb.tsfile.write.record.Tablet;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TsFileLoaderTool {
  private static final int MAX_TABLET_LENGTH = 1024 * 64;

  private static String host = "localhost";
  private static String port = "6667";
  private static String user = "root";
  private static String password = "root";
  private static String filePath = "";

  private static Map<String, Set<MeasurementSchema>> device2Measurements;

  public static void main(String[] args) {
    Session session = null;
    try {
      parseArgs(args);
      session = new Session(host, port, user, password);
      session.open();
      System.out.printf("Connect to IoTDB %s:%s successfully.%n", host, port);
      writeToIoTDB(collectTsFiles(new File(filePath)), session);
    } catch (IoTDBConnectionException e) {
      System.out.printf("Can not connect to IoTDB. %s%n", e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      System.out.printf("Load Error. %s%n", e.getMessage());
      e.printStackTrace();
    } finally {
      if (session != null) {
        try {
          session.close();
        } catch (IoTDBConnectionException e) {
          System.out.printf("Can not connect to IoTDB. %s%n", e.getMessage());
          e.printStackTrace();
        }
      }
    }
  }

  public static void parseArgs(String[] args) {
    Options options = createOptions();
    try {
      CommandLine commandLine = new DefaultParser().parse(options, args);
      host = getArgOrDefault(commandLine, "h", host);
      port = getArgOrDefault(commandLine, "p", port);
      user = getArgOrDefault(commandLine, "u", user);
      password = getArgOrDefault(commandLine, "pw", password);
      filePath = getArgOrDefault(commandLine, "f", filePath);
    } catch (ParseException e) {
      System.out.printf("Parse Args Error. %s%n", e.getMessage());
      priHelp(options);
    }
  }

  private static void priHelp(Options options) {
    new HelpFormatter().printHelp("./load-tsfile.sh(load-tsfile.bat if Windows)", options, true);
  }

  private static String getArgOrDefault(
      CommandLine commandLine, String argName, String defaultValue) {
    String value = commandLine.getOptionValue(argName);
    return value == null ? defaultValue : value;
  }

  public static Options createOptions() {
    Options options = new Options();
    Option help = new Option("help", false, "Display help information(optional)");
    help.setRequired(false);
    options.addOption(help);

    Option host =
        Option.builder("h")
            .argName("host")
            .hasArg()
            .desc("Host Name (optional, default 127.0.0.1)")
            .build();
    options.addOption(host);

    Option port =
        Option.builder("p").argName("port").hasArg().desc("Port (optional, default 6667)").build();
    options.addOption(port);

    Option username =
        Option.builder("u")
            .argName("username")
            .hasArg()
            .desc("User name (required)")
            .required()
            .build();
    options.addOption(username);

    Option password =
        Option.builder("pw").argName("password").hasArg().desc("password (optional)").build();
    options.addOption(password);

    Option filePathOpt =
        Option.builder("f")
            .argName("file")
            .hasArg()
            .desc("File or Dictionary to be loaded.")
            .required()
            .build();
    options.addOption(filePathOpt);
    return options;
  }

  public static List<File> collectTsFiles(File file) {
    if (file.isFile()) {
      return file.getName().endsWith(TsFileConstant.TSFILE_SUFFIX)
          ? Collections.singletonList(file)
          : Collections.emptyList();
    }
    List<File> list = new ArrayList<>();
    for (File listFile : file.listFiles()) {
      if (listFile.isDirectory()) {
        list.addAll(collectTsFiles(listFile));
      } else if (listFile.getName().endsWith(TsFileConstant.TSFILE_SUFFIX)) {
        list.add(listFile);
      }
    }
    return list;
  }

  /**
   * write a list of file to IoTDB with session.
   *
   * @param files a list of file to write to IoTDB
   * @param session IoTDB session
   */
  public static void writeToIoTDB(List<File> files, Session session) {
    sortTsFiles(files);
    int size = files.size();
    List<File> unloadTsFiles = new ArrayList<>();
    System.out.printf("Collect TsFiles successfully, %d files to be loaded.%n", size);
    System.out.println("Start Loading TsFiles...");
    for (int i = 0; i < size; i++) {
      File file = files.get(i);
      System.out.printf("Loading %s(%d/%d)...", file.getPath(), i + 1, size);
      try {
        writeTsFile(file.getPath(), session);
      } catch (Exception e) {
        System.out.println(
            "------------------------------Error Message------------------------------");
        e.printStackTrace();
        System.out.println(
            "------------------------------End Message------------------------------");
        unloadTsFiles.add(file);
        continue;
      }
      System.out.println("Done");
    }
    System.out.println("Finish Loading TsFiles");
    System.out.printf(
        "Load %d TsFiles successfully, %d TsFiles not loaded.%n",
        size - unloadTsFiles.size(), unloadTsFiles.size());
    if (!unloadTsFiles.isEmpty()) {
      System.out.println("Load Error TsFiles list");
      for (File file : unloadTsFiles) {
        System.out.println(file.getPath());
      }
    }
  }

  private static void sortTsFiles(List<File> files) {
    Map<File, Long> file2Timestamp = new HashMap<>();
    Map<File, Long> file2Version = new HashMap<>();
    for (File file : files) {
      String[] splitStrings = file.getName().split(FilePathUtils.FILE_NAME_SEPARATOR);
      file2Timestamp.put(file, Long.parseLong(splitStrings[0]));
      file2Version.put(file, Long.parseLong(splitStrings[1]));
    }

    Collections.sort(
        files,
        (o1, o2) -> {
          long timestampDiff = file2Timestamp.get(o1) - file2Timestamp.get(o2);
          if (timestampDiff != 0) {
            return (int) (timestampDiff);
          }
          return (int) (file2Version.get(o1) - file2Version.get(o2));
        });
  }

  /**
   * Read a TsFile and write into IoTDB session. This method can load TsFile with IoTDB version.
   * Support TsFile generated from IoTDB version 0.12 - 0.14(including Aligned Timeseries).
   *
   * @param filename the file path to be loaded
   * @param session IoTDB session
   */
  public static void writeTsFile(String filename, Session session)
      throws IOException, IllegalPathException, IoTDBConnectionException,
          StatementExecutionException, NoMeasurementException {
    // parse modifications from .mods
    List<Modification> modifications = null;
    if (FSFactoryProducer.getFSFactory()
        .getFile(filename + ModificationFile.FILE_SUFFIX)
        .exists()) {
      modifications =
          (List<Modification>)
              new ModificationFile(filename + ModificationFile.FILE_SUFFIX).getModifications();
    }

    // read all device and their measurements
    parseDeviceFromTsFile(filename);

    try (TsFileSequenceReader reader = new TsFileSequenceReader(filename)) {
      for (Map.Entry<String, Set<MeasurementSchema>> entry : device2Measurements.entrySet()) {
        // collect measurements for device
        boolean isAligned = false;
        String curDevice = entry.getKey();
        List<MeasurementSchema> measurementSchemas = new ArrayList<>();
        ArrayList<Path> paths = new ArrayList<>();
        for (MeasurementSchema measurementSchema : entry.getValue()) {
          if (!measurementSchema.getType().equals(TSDataType.VECTOR)) {
            measurementSchemas.add(measurementSchema);
          } else {
            isAligned = true;
          }
        }
        for (MeasurementSchema measurementSchema : measurementSchemas) {
          paths.add(new Path(curDevice, measurementSchema.getMeasurementId()));
        }

        // construct query to this tsfile
        List<AbstractFileSeriesReader> readersOfSelectedSeries = new ArrayList<>();
        List<TSDataType> dataTypes = new ArrayList<>();
        IMetadataQuerier metadataQuerier = new MetadataQuerierByFileImpl(reader);
        IChunkLoader chunkLoader = new CachedChunkLoaderImpl(reader);
        for (Path path : paths) {
          List<IChunkMetadata> chunkMetadataList = metadataQuerier.getChunkMetaDataList(path);
          modifyChunkMetadata(isAligned, path, chunkMetadataList, modifications);
          AbstractFileSeriesReader seriesReader;
          if (chunkMetadataList.isEmpty()) {
            seriesReader = new EmptyFileSeriesReader();
            dataTypes.add(metadataQuerier.getDataType(path));
          } else {
            seriesReader = new FileSeriesReader(chunkLoader, chunkMetadataList, null);
            dataTypes.add(chunkMetadataList.get(0).getDataType());
          }
          readersOfSelectedSeries.add(seriesReader);
        }

        // read data from tsfile and construct session to send to IoTDB
        QueryDataSet dataSet =
            new DataSetWithoutTimeGenerator(paths, dataTypes, readersOfSelectedSeries);
        Tablet tablet = new Tablet(curDevice, measurementSchemas, MAX_TABLET_LENGTH);
        tablet.initBitMaps();
        int measurementSize = measurementSchemas.size();
        while (dataSet.hasNext()) {
          RowRecord rowRecord = dataSet.next();
          tablet.addTimestamp(tablet.rowSize, rowRecord.getTimestamp());
          for (int i = 0; i < measurementSize; i++) {
            Field field = rowRecord.getFields().get(i);
            if (field == null) {
              tablet.bitMaps[i].mark(tablet.rowSize);
            } else {
              tablet.addValue(
                  measurementSchemas.get(i).getMeasurementId(),
                  tablet.rowSize,
                  field.getObjectValue(field.getDataType()));
            }
          }
          tablet.rowSize++;
          if (tablet.rowSize == MAX_TABLET_LENGTH) {
            if (isAligned) {
              session.insertAlignedTablet(tablet);
            } else {
              session.insertTablet(tablet);
            }
            tablet.reset();
          }
        }
        if (isAligned) {
          session.insertAlignedTablet(tablet);
        } else {
          session.insertTablet(tablet);
        }
      }
    }
  }

  private static void parseDeviceFromTsFile(String filename) throws IOException {
    device2Measurements = new HashMap<>();
    try (TsFileSequenceReader reader = new TsFileSequenceReader(filename)) {
      reader.position((long) TSFileConfig.MAGIC_STRING.getBytes().length + 1);
      String curDevice = null;
      byte marker;
      while ((marker = reader.readMarker()) != MetaMarker.SEPARATOR) {
        switch (marker) {
          case MetaMarker.CHUNK_HEADER:
          case MetaMarker.TIME_CHUNK_HEADER:
          case MetaMarker.VALUE_CHUNK_HEADER:
          case MetaMarker.ONLY_ONE_PAGE_CHUNK_HEADER:
          case MetaMarker.ONLY_ONE_PAGE_TIME_CHUNK_HEADER:
          case MetaMarker.ONLY_ONE_PAGE_VALUE_CHUNK_HEADER:
            ChunkHeader header = reader.readChunkHeader(marker);
            MeasurementSchema measurementSchema =
                new MeasurementSchema(
                    header.getMeasurementID(),
                    header.getDataType(),
                    header.getEncodingType(),
                    header.getCompressionType());
            device2Measurements
                .computeIfAbsent(curDevice, o -> new HashSet<>())
                .add(measurementSchema);
            reader.position(reader.position() + header.getDataSize());
            break;
          case MetaMarker.CHUNK_GROUP_HEADER:
            ChunkGroupHeader chunkGroupHeader = reader.readChunkGroupHeader();
            curDevice = chunkGroupHeader.getDeviceID();
            break;
          case MetaMarker.OPERATION_INDEX_RANGE:
            reader.readPlanIndex();
            break;
          default:
            MetaMarker.handleUnexpectedMarker(marker);
        }
      }
    }
  }

  private static void modifyChunkMetadata(
      boolean isAligned,
      Path path,
      List<IChunkMetadata> chunkMetadataList,
      List<Modification> modifications)
      throws IllegalPathException {
    if (modifications == null || modifications.isEmpty()) {
      return;
    }
    List<Modification> measurementModifications = new ArrayList<>();
    Iterator<Modification> modsIterator = modifications.listIterator();
    Deletion currentDeletion;
    while (modsIterator.hasNext()) {
      currentDeletion = (Deletion) modsIterator.next();
      // if deletion path match the chunkPath, then add the deletion to the list
      if (currentDeletion.getPath().matchFullPath(new PartialPath(path.getFullPath()))) {
        measurementModifications.add(currentDeletion);
      }
    }
    if (!isAligned) {
      QueryUtils.modifyChunkMetaData(chunkMetadataList, measurementModifications);
    } else {
      List<AlignedChunkMetadata> alignedChunkMetadataList = new ArrayList<>();
      for (IChunkMetadata chunkMetadata : chunkMetadataList) {
        alignedChunkMetadataList.add((AlignedChunkMetadata) chunkMetadata);
      }
      // AlignedChunk only contains one valueChunkMetadata which is measurement with this path
      QueryUtils.modifyAlignedChunkMetaData(
          alignedChunkMetadataList, Collections.singletonList(measurementModifications));
    }
  }
}
