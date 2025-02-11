package io.github.linkedfactory.kvin.parquet;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.github.linkedfactory.kvin.Kvin;
import io.github.linkedfactory.kvin.KvinListener;
import io.github.linkedfactory.kvin.KvinTuple;
import io.github.linkedfactory.kvin.Record;
import io.github.linkedfactory.kvin.util.AggregatingIterator;
import io.github.linkedfactory.kvin.util.Values;
import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.NiceIterator;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.reflect.ReflectData;
import org.apache.commons.collections.map.HashedMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterApi;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.filter2.predicate.Statistics;
import org.apache.parquet.filter2.predicate.UserDefinedPredicate;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.hadoop.util.HadoopOutputFile;
import org.apache.parquet.io.api.Binary;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.*;

import static org.apache.parquet.filter2.predicate.FilterApi.*;

public class KvinParquet implements Kvin {

    final static ReflectData reflectData = new ReflectData(KvinParquet.class.getClassLoader());
    // parquet file writer config
    static final long ROW_GROUP_SIZE = 1048576;  // 1 MB
    static final int PAGE_SIZE = 8192; // 8 KB
    static final int DICT_PAGE_SIZE = 1048576; // 1 MB
    static final int ZSTD_COMPRESSION_LEVEL = 12; // 1 - 22
    Map<Path, HadoopInputFile> inputFileCache = new HashMap<>(); // hadoop input file cache
    Cache<Long, String> propertyIdReverseLookUpCache = CacheBuilder.newBuilder().maximumSize(10000).build();
    String archiveLocation;
    // data file schema
    Schema kvinTupleSchema = SchemaBuilder.record("KvinTupleInternal").namespace(KvinParquet.class.getName()).fields()
            .name("id").type().nullable().bytesType().noDefault()
            .name("time").type().longType().noDefault()
            .name("seqNr").type().intType().intDefault(0)
            .name("valueInt").type().nullable().intType().noDefault()
            .name("valueLong").type().nullable().longType().noDefault()
            .name("valueFloat").type().nullable().floatType().noDefault()
            .name("valueDouble").type().nullable().doubleType().noDefault()
            .name("valueString").type().nullable().stringType().noDefault()
            .name("valueBool").type().nullable().intType().noDefault()
            .name("valueObject").type().nullable().bytesType().noDefault().endRecord();
    // mapping file schema
    Schema itemMappingSchema = SchemaBuilder.record("ItemMapping").namespace(KvinParquet.class.getName()).fields()
            .name("itemId").type().longType().noDefault()
            .name("item").type().stringType().noDefault().endRecord();
    Schema propertyMappingSchema = SchemaBuilder.record("PropertyMapping").namespace(KvinParquet.class.getName()).fields()
            .name("propertyId").type().longType().noDefault()
            .name("property").type().stringType().noDefault().endRecord();
    Schema contextMappingSchema = SchemaBuilder.record("ContextMapping").namespace(KvinParquet.class.getName()).fields()
            .name("contextId").type().longType().noDefault()
            .name("context").type().stringType().noDefault().endRecord();
    long itemIdCounter = 0, propertyIdCounter = 0, contextIdCounter = 0; // global id counter
    // used by writer
    Map<String, Long> itemMap = new HashMap<>();
    Map<String, Long> propertyMap = new HashMap<>();
    Map<String, Long> contextMap = new HashMap<>();
    // used by reader
    Cache<MappingCacheKeyTuple<URI>, Map<String, Mapping>> idMappingCache = CacheBuilder.newBuilder().maximumSize(20000).build();

    public KvinParquet(String archiveLocation) {
        this.archiveLocation = archiveLocation;
    }

    private Mapping fetchMappingIds(Path mappingFile, FilterPredicate filter) throws IOException {
        Mapping id;
        HadoopInputFile inputFile = getFile(mappingFile);
        try (ParquetReader<Mapping> reader = AvroParquetReader.<Mapping>builder(inputFile)
                .withDataModel(reflectData)
                .useStatsFilter()
                .withFilter(FilterCompat.get(filter))
                .build()) {
            id = reader.read();
        }
        return id;
    }

    private HadoopInputFile getFile(Path path) {
        HadoopInputFile inputFile;
        synchronized (inputFileCache) {
            inputFile = inputFileCache.get(path);
            if (inputFile == null) {
                try {
                    inputFile = HadoopInputFile.fromPath(path, new Configuration());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                inputFileCache.put(path, inputFile);
            }
        }
        return inputFile;
    }

    @Override
    public boolean addListener(KvinListener listener) {
        return false;
    }

    @Override
    public boolean removeListener(KvinListener listener) {
        return false;
    }

    @Override
    public void put(KvinTuple... tuples) {
        this.put(Arrays.asList(tuples));
    }

    @Override
    public void put(Iterable<KvinTuple> tuples) {
        try {
            putInternal(tuples);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void putInternal(Iterable<KvinTuple> tuples) throws IOException {
        // data writer
        Path dataFile = null;
        ParquetWriter<KvinTupleInternal> parquetDataWriter = null;

        // mapping writer
        Path itemMappingFile, propertyMappingFile, contextMappingFile;
        ParquetWriter<Object> itemMappingWriter = null, propertyMappingWriter = null, contextMappingWriter = null;

        //  state variables
        boolean writingToExistingYearFolder = false;
        Long nextChunkTimestamp = null;
        Calendar prevTupleDate = null;

        // initial partition key
        byte[] initialPartitionKey = generatePartitionKey(1L, 1L, 1L);
        byte[] weekPartitionKey = initialPartitionKey, yearPartitionKey = initialPartitionKey;

        for (KvinTuple tuple : tuples) {
            KvinTupleInternal internalTuple = new KvinTupleInternal();
            nextChunkTimestamp = initNextChunkTimestamp(nextChunkTimestamp, tuple);

            // initializing writers to data and mapping file along with the initial folders.
            if (dataFile == null) {
                int year = getDate(tuple.time).get(Calendar.YEAR);
                // new year and week folder
                if (!getExistingYears().contains(year)) {
                    dataFile = new Path(archiveLocation + getDate(tuple.time).get(Calendar.YEAR), "temp/data.parquet");
                } else {
                    // existing year and week folder
                    File existingYearFolder = getExistingYearFolder(year);
                    String existingYearFolderPath = existingYearFolder.getAbsolutePath();

                    dataFile = new Path(existingYearFolderPath, "temp/data.parquet");
                    yearPartitionKey = decodePartitionKey(Long.parseLong(existingYearFolder.getName().split("_")[0])); // minOfItemIdOfAllTheWeeks; // minOfItemIdOfAllTheWeeks
                    writingToExistingYearFolder = true;
                }
                // mapping file writers init
                itemMappingFile = new Path(archiveLocation, "metadata/itemMapping.parquet");
                propertyMappingFile = new Path(archiveLocation, "metadata/propertyMapping.parquet");
                contextMappingFile = new Path(archiveLocation, "metadata/contextMapping.parquet");

                parquetDataWriter = getParquetDataWriter(dataFile);
                itemMappingWriter = getParquetMappingWriter(itemMappingFile, itemMappingSchema);
                propertyMappingWriter = getParquetMappingWriter(propertyMappingFile, propertyMappingSchema);
                contextMappingWriter = getParquetMappingWriter(contextMappingFile, contextMappingSchema);
            }

            // partitioning file on week change
            if (tuple.time >= nextChunkTimestamp) {
                // renaming current week folder with partition key name. ( at the start, while writing into the current week folder data and mapping files, the folder name is set to "temp".)
                // key: WeekMinItemPropertyContextId_WeekMaxItemPropertyContextId
                renameFolder(dataFile, weekPartitionKey, generatePartitionKey(itemIdCounter, propertyIdCounter, contextIdCounter));

                // updating partition key of the folder with the max itemId of the newly added week folder
                // key: YearMinItemPropertyContextId_YearMaxItemPropertyContextId
                if (writingToExistingYearFolder)
                    renameFolder(dataFile, yearPartitionKey, generatePartitionKey(itemIdCounter, propertyIdCounter, contextIdCounter), prevTupleDate.get(Calendar.YEAR));

                // updating new week partition id
                long tempItemId = itemIdCounter, tempPropertyId = propertyIdCounter, tempContextId = contextIdCounter;
                if (isNeedForIdChange(tuple, idType.ITEM_ID)) {
                    tempItemId++;
                }
                if (isNeedForIdChange(tuple, idType.PROPERTY_ID)) {
                    tempPropertyId++;
                }
                if (isNeedForIdChange(tuple, idType.CONTEXT_ID)) {
                    tempContextId++;
                }
                weekPartitionKey = generatePartitionKey(tempItemId, tempPropertyId, tempContextId);

                // adding 1 week to the current tuple timestamp and marking the timestamp to consider as a change of the week.
                nextChunkTimestamp = getNextChunkTimestamp(tuple.time);

                // handling year change
                if (prevTupleDate.get(Calendar.YEAR) != getDate(tuple.time).get(Calendar.YEAR)) {
                    // updating the partition key of the year folder if it was created without the partition key.
                    if (!writingToExistingYearFolder) {
                        renameFolder(dataFile, yearPartitionKey, generatePartitionKey(itemIdCounter, propertyIdCounter, contextIdCounter), prevTupleDate.get(Calendar.YEAR));
                    }
                    yearPartitionKey = generatePartitionKey(itemIdCounter, propertyIdCounter, contextIdCounter);
                    writingToExistingYearFolder = false;
                }

                // create new week folder in case of year change.
                if (!writingToExistingYearFolder) {
                    dataFile = new Path(archiveLocation + getDate(tuple.time).get(Calendar.YEAR), "temp/data.parquet");
                } else {
                    // create new week folder under existing year folder for the same year.
                    int year = getDate(tuple.time).get(Calendar.YEAR);
                    File existingYearFolder = getExistingYearFolder(year);
                    dataFile = new Path(existingYearFolder.getAbsolutePath(), "temp/data.parquet");
                }
                parquetDataWriter.close();
                parquetDataWriter = getParquetDataWriter(dataFile);
            }

            // writing mappings and values
            internalTuple.setId(generateId(tuple, itemMappingWriter, propertyMappingWriter, contextMappingWriter));
            internalTuple.setTime(tuple.time);
            internalTuple.setSeqNr(tuple.seqNr);

            internalTuple.setValueInt(tuple.value instanceof Integer ? (int) tuple.value : null);
            internalTuple.setValueLong(tuple.value instanceof Long ? (long) tuple.value : null);
            internalTuple.setValueFloat(tuple.value instanceof Float ? (float) tuple.value : null);
            internalTuple.setValueDouble(tuple.value instanceof Double ? (double) tuple.value : null);
            internalTuple.setValueString(tuple.value instanceof String ? (String) tuple.value : null);
            internalTuple.setValueBool(tuple.value instanceof Boolean ? (Boolean) tuple.value ? 1 : 0 : null);
            if (tuple.value instanceof Record || tuple.value instanceof URI || tuple.value instanceof BigInteger || tuple.value instanceof BigDecimal || tuple.value instanceof Short) {
                internalTuple.setValueObject(encodeRecord(tuple.value));
            } else {
                internalTuple.setValueObject(null);
            }
            parquetDataWriter.write(internalTuple);
            prevTupleDate = getDate(tuple.time);
        }
        // updating last written week folder's partition key - for including last "WeekMaxItemPropertyContextId" for the week.
        renameFolder(dataFile, weekPartitionKey, generatePartitionKey(itemIdCounter, propertyIdCounter, contextIdCounter));
        // updating last written year folder's partition key - for including last "YearMaxItemPropertyContextId".
        renameFolder(dataFile, yearPartitionKey, generatePartitionKey(itemIdCounter, propertyIdCounter, contextIdCounter), prevTupleDate.get(Calendar.YEAR));
        itemMappingWriter.close();
        propertyMappingWriter.close();
        contextMappingWriter.close();
        parquetDataWriter.close();
    }

    private ParquetWriter<KvinTupleInternal> getParquetDataWriter(Path dataFile) throws IOException {
        Configuration writerConf = new Configuration();
        writerConf.setInt("parquet.zstd.compressionLevel", ZSTD_COMPRESSION_LEVEL);
        return AvroParquetWriter.<KvinTupleInternal>builder(HadoopOutputFile.fromPath(dataFile, new Configuration()))
                .withSchema(kvinTupleSchema)
                .withConf(writerConf)
                .withDictionaryEncoding(true)
                .withCompressionCodec(CompressionCodecName.ZSTD)
                //.withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupSize(ROW_GROUP_SIZE)
                .withPageSize(PAGE_SIZE)
                .withDictionaryPageSize(DICT_PAGE_SIZE)
                .withDataModel(reflectData)
                .build();
    }

    private ParquetWriter<Object> getParquetMappingWriter(Path dataFile, Schema schema) throws IOException {
        Configuration writerConf = new Configuration();
        writerConf.setInt("parquet.zstd.compressionLevel", 12);
        return AvroParquetWriter.builder(HadoopOutputFile.fromPath(dataFile, new Configuration()))
                .withSchema(schema)
                .withConf(writerConf)
                .withDictionaryEncoding(true)
                .withCompressionCodec(CompressionCodecName.ZSTD)
                //.withCompressionCodec(CompressionCodecName.SNAPPY)
                .withRowGroupSize(ROW_GROUP_SIZE)
                .withPageSize(PAGE_SIZE)
                .withDictionaryPageSize(DICT_PAGE_SIZE)
                .withDataModel(reflectData)
                .build();
    }

    private Long initNextChunkTimestamp(Long nextChunkTimestamp, KvinTuple currentTuple) {
        // adding 1 week to the initial tuple timestamp and marking the timestamp to consider as a change of the week.
        if (nextChunkTimestamp == null) nextChunkTimestamp = getNextChunkTimestamp(currentTuple.time);
        return nextChunkTimestamp;
    }

    private long getNextChunkTimestamp(long currentTimestamp) {
        // adds 1 week to the given timestamp
        return currentTimestamp + 604800;
    }

    private void renameFolder(Path file, byte[] newMin, byte[] newMax) throws IOException {
        java.nio.file.Path currentFolder = Paths.get(file.getParent().toString());
        Files.move(currentFolder, currentFolder.resolveSibling(encodePartitionKey(newMin) + "_" + encodePartitionKey(newMax)));
    }

    private void renameFolder(Path file, byte[] min, byte[] max, int year) throws IOException {
        java.nio.file.Path currentFolder = Paths.get(file.getParent().getParent().toString());
        Files.move(currentFolder, currentFolder.resolveSibling(encodePartitionKey(min) + "_" + encodePartitionKey(max) + "_" + year));
    }

    private byte[] generatePartitionKey(Long itemId, Long propertyId, Long contextId) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 3);
        buffer.putLong(itemId);
        buffer.putLong(propertyId);
        buffer.putLong(contextId);
        return buffer.array();
    }

    private Map<String, Long> readPartitionKey(long key) {
        ByteBuffer keyBuffer = ByteBuffer.wrap(decodePartitionKey(key));
        Map<String, Long> partitionKey = new HashedMap();
        partitionKey.put("itemId", keyBuffer.getLong());
        partitionKey.put("propertyId", keyBuffer.getLong());
        partitionKey.put("contextId", keyBuffer.getLong());
        return partitionKey;
    }

    private Long encodePartitionKey(byte[] key) {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.put(key);
        buffer.flip();
        return buffer.getLong();
    }

    private byte[] decodePartitionKey(Long key) {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        buffer.putLong(key);
        buffer.flip();
        return buffer.array();
    }

    private ArrayList<Integer> getExistingYears() {
        ArrayList<Integer> existingYears = new ArrayList<>();
        File[] yearFolders = new File(archiveLocation).listFiles();
        if (yearFolders != null) {
            for (File yearFolder : yearFolders) {
                String yearFolderName = yearFolder.getName();
                if (!yearFolderName.startsWith("metadata")) {
                    int year = Integer.parseInt(yearFolderName.split("_")[2]);
                    if (!existingYears.contains(year)) existingYears.add(year);
                }
            }
        }
        return existingYears;
    }

    private File getExistingYearFolder(int existingYear) {
        File[] yearFolders = new File(archiveLocation).listFiles();
        File existingYearFolder = null;
        for (File yearFolder : yearFolders) {
            if (!yearFolder.getName().startsWith("metadata")) {
                int year = Integer.parseInt(yearFolder.getName().split("_")[2]);
                if (year == existingYear) {
                    existingYearFolder = yearFolder;
                    break;
                }
            }
        }
        return existingYearFolder;
    }

    private Calendar getDate(long timestamp) {
        Timestamp ts = new Timestamp(timestamp * 1000);
        Date date = new java.sql.Date(ts.getTime());
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar;
    }

    private byte[] generateId(KvinTuple currentTuple, ParquetWriter itemMappingWriter, ParquetWriter propertyMappingWriter, ParquetWriter contextMappingWriter) throws IOException {
        Long itemId, propertyId, contextId;

        if (itemIdCounter == 0 || isNeedForIdChange(currentTuple, idType.ITEM_ID)) {
            itemId = ++itemIdCounter;
            itemMap.put(currentTuple.item.toString(), itemId);
            ItemMapping itemMapping = new ItemMapping();
            itemMapping.setMappingId(itemId);
            itemMapping.setMappingValue(currentTuple.item.toString());
            itemMappingWriter.write(itemMapping);
        } else {
            itemId = itemMap.get(currentTuple.item.toString());
        }

        if (propertyIdCounter == 0 || isNeedForIdChange(currentTuple, idType.PROPERTY_ID)) {
            propertyId = ++propertyIdCounter;
            propertyMap.put(currentTuple.property.toString(), propertyId);
            PropertyMapping propertyMapping = new PropertyMapping();
            propertyMapping.setMappingId(propertyId);
            propertyMapping.setMappingValue(currentTuple.property.toString());
            propertyMappingWriter.write(propertyMapping);
        } else {
            propertyId = propertyMap.get(currentTuple.property.toString());
        }

        if (contextIdCounter == 0 || isNeedForIdChange(currentTuple, idType.CONTEXT_ID)) {
            contextId = ++contextIdCounter;
            contextMap.put(currentTuple.context.toString(), contextId);
            ContextMapping contextMapping = new ContextMapping();
            contextMapping.setMappingId(contextId);
            contextMapping.setMappingValue(currentTuple.context.toString());
            contextMappingWriter.write(contextMapping);
        } else {
            contextId = contextMap.get(currentTuple.context.toString());
        }

        ByteBuffer idBuffer = ByteBuffer.allocate(Long.BYTES * 3);
        idBuffer.putLong(itemId);
        idBuffer.putLong(propertyId);
        idBuffer.putLong(contextId);
        return idBuffer.array();
    }

    private boolean isNeedForIdChange(KvinTuple currentTuple, idType type) {
        boolean result = false;

        switch (type) {
            case ITEM_ID:
                result = !itemMap.containsKey(currentTuple.item.toString());
                break;
            case PROPERTY_ID:
                result = !propertyMap.containsKey(currentTuple.property.toString());
                break;
            case CONTEXT_ID:
                result = !contextMap.containsKey(currentTuple.context.toString());
                break;

        }
        return result;
    }

    private byte[] encodeRecord(Object record) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        if (record instanceof Record) {
            Record r = (Record) record;
            byteArrayOutputStream.write("O".getBytes(StandardCharsets.UTF_8));
            byte[] propertyBytes = r.getProperty().toString().getBytes();
            byteArrayOutputStream.write((byte) propertyBytes.length);
            byteArrayOutputStream.write(propertyBytes);
            byteArrayOutputStream.write(encodeRecord(r.getValue()));
        } else if (record instanceof URI) {
            URI uri = (URI) record;
            byte[] uriIndicatorBytes = "R".getBytes(StandardCharsets.UTF_8);
            byte[] uriBytes = new byte[uri.toString().getBytes().length + 1];
            uriBytes[0] = (byte) uri.toString().getBytes().length;
            System.arraycopy(uri.toString().getBytes(), 0, uriBytes, 1, uriBytes.length - 1);

            byte[] combinedBytes = new byte[uriIndicatorBytes.length + uriBytes.length];
            System.arraycopy(uriIndicatorBytes, 0, combinedBytes, 0, uriIndicatorBytes.length);
            System.arraycopy(uriBytes, 0, combinedBytes, uriIndicatorBytes.length, uriBytes.length);
            return combinedBytes;
        } else {
            return Values.encode(record);
        }
        return byteArrayOutputStream.toByteArray();
    }

    private Map<String, Mapping> getIdMapping(URI item, URI property, URI context) throws IOException {
        Mapping itemMapping, propertyMapping, contextMapping;
        Map<String, Mapping> idMappings;

        Map<String, Mapping> cachedMappingEntry = fetchMappingIdsFromCache(item, property, context);
        if (cachedMappingEntry == null) {
            // reading from files
            idMappings = new HashMap<>();
            if (item != null) {
                FilterPredicate filter = eq(FilterApi.binaryColumn("item"), Binary.fromString(item.toString()));
                Path mappingFile = new Path(this.archiveLocation + "metadata/itemMapping.parquet");
                itemMapping = fetchMappingIds(mappingFile, filter);
                idMappings.put("itemMapping", itemMapping);
            } else {
                idMappings.put("itemMapping", null);
            }

            if (property != null) {
                FilterPredicate filter = eq(FilterApi.binaryColumn("property"), Binary.fromString(property.toString()));
                Path mappingFile = new Path(this.archiveLocation + "metadata/propertyMapping.parquet");
                propertyMapping = fetchMappingIds(mappingFile, filter);
                idMappings.put("propertyMapping", propertyMapping);
            } else {
                idMappings.put("propertyMapping", null);
            }

            if (context != null) {
                FilterPredicate filter = eq(FilterApi.binaryColumn("context"), Binary.fromString(context.toString()));
                Path mappingFile = new Path(this.archiveLocation + "metadata/contextMapping.parquet");
                contextMapping = fetchMappingIds(mappingFile, filter);
                idMappings.put("contextMapping", contextMapping);
            } else {
                idMappings.put("contextMapping", null);
            }
            writeMappingIdsToCache(item, property, context, idMappings);
        } else {
            idMappings = cachedMappingEntry;
        }
        return idMappings;
    }

    private Map<String, Mapping> fetchMappingIdsFromCache(URI item, URI property, URI context) {
        MappingCacheKeyTuple<URI> key = getIdMappingCacheKey(item, property, context);
        return idMappingCache.getIfPresent(key);
    }

    private void writeMappingIdsToCache(URI item, URI property, URI context, Map<String, Mapping> idMapping) {
        MappingCacheKeyTuple<URI> key = getIdMappingCacheKey(item, property, context);
        idMappingCache.put(key, idMapping);
    }

    private MappingCacheKeyTuple<URI> getIdMappingCacheKey(URI item, URI property, URI context) {
        return new MappingCacheKeyTuple<>(item, property, context);
    }

    private Object decodeRecord(byte[] data) {
        Record r = null;
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data)) {
            char type = (char) byteArrayInputStream.read();
            if (type == 'O') {
                int propertyLength = byteArrayInputStream.read();
                String property = new String(byteArrayInputStream.readNBytes(propertyLength), StandardCharsets.UTF_8);
                var value = decodeRecord(byteArrayInputStream.readAllBytes());
                if (r != null) {
                    r.append(new Record(URIs.createURI(property), value));
                } else {
                    r = new Record(URIs.createURI(property), value);
                }
            } else if (type == 'R') {
                int uriLength = byteArrayInputStream.read();
                String uri = new String(byteArrayInputStream.readNBytes(uriLength), StandardCharsets.UTF_8);
                return URIs.createURI(uri);
            } else {
                return Values.decode(data);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    private FilterPredicate generateFetchFilter(Map<String, Mapping> mappings) {
        FilterPredicate predicate = null;

        if (mappings.get("propertyMapping") != null) {
            ByteBuffer keyBuffer = ByteBuffer.allocate(Long.BYTES * 3);
            keyBuffer.putLong(mappings.get("itemMapping").getMappingId());
            keyBuffer.putLong(mappings.get("propertyMapping").getMappingId());
            keyBuffer.putLong(mappings.get("contextMapping").getMappingId());
            predicate = eq(FilterApi.binaryColumn("id"), Binary.fromConstantByteArray(keyBuffer.array()));
        } else if (mappings.get("propertyMapping") == null) {
            ByteBuffer keyBuffer = ByteBuffer.allocate(Long.BYTES);
            keyBuffer.putLong(mappings.get("itemMapping").getMappingId());
            predicate = and(gt(FilterApi.binaryColumn("id"), Binary.fromConstantByteArray(keyBuffer.array())),
                    lt(FilterApi.binaryColumn("id"),
                            Binary.fromConstantByteArray(ByteBuffer.allocate(Long.BYTES)
                                    .putLong(mappings.get("itemMapping").getMappingId() + 1).array())));
        }
        return predicate;
    }

    @Override
    public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long limit) {
        return fetchInternal(item, property, context, null, null, limit, null, null);
    }

    @Override
    public IExtendedIterator<KvinTuple> fetch(URI item, URI property, URI context, long end, long begin, long limit, long interval, String op) {
        IExtendedIterator<KvinTuple> internalResult = fetchInternal(item, property, context, end, begin, limit, interval, op);
        if (op != null) {
            internalResult = new AggregatingIterator<>(internalResult, interval, op.trim().toLowerCase(), limit) {
                @Override
                protected KvinTuple createElement(URI item, URI property, URI context, long time, int seqNr, Object value) {
                    return new KvinTuple(item, property, context, time, seqNr, value);
                }
            };
        }
        return internalResult;
    }

    public String getProperty(KvinTupleInternal tuple) {
        ByteBuffer idBuffer = ByteBuffer.wrap(tuple.getId());
        idBuffer.getLong();
        Long propertyId = idBuffer.getLong();
        String cachedProperty = propertyIdReverseLookUpCache.getIfPresent(propertyId);

        if (cachedProperty == null) {
            try {
                FilterPredicate filter = eq(FilterApi.longColumn("propertyId"), propertyId);
                Path mappingFile = new Path(archiveLocation + "metadata/propertyMapping.parquet");
                Mapping propertyMapping = null;
                propertyMapping = fetchMappingIds(mappingFile, filter);
                cachedProperty = propertyMapping.getMappingValue();
                propertyIdReverseLookUpCache.put(propertyId, propertyMapping.getMappingValue());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return cachedProperty;
    }

    private IExtendedIterator<KvinTuple> fetchInternal(URI item, URI property, URI context, Long end, Long begin, Long limit, Long interval, String op) {
        try {
            // filters
            Map<String, Mapping> idMappings = getIdMapping(item, property, context);

            if (idMappings.size() == 0) {
                return NiceIterator.emptyIterator();
            }

            FilterPredicate filter = generateFetchFilter(idMappings);
            ArrayList<Path> dataFiles = getFilePath(idMappings);
            ArrayList<ParquetReader<KvinTupleInternal>> readers = new ArrayList<>();

            // data readers
            for (Path path : dataFiles) {
                HadoopInputFile inputFile = getFile(path);
                readers.add(AvroParquetReader.<KvinTupleInternal>builder(inputFile)
                        .withDataModel(reflectData)
                        .useStatsFilter()
                        .withFilter(FilterCompat.get(filter))
                        .build());
            }
            return new NiceIterator<KvinTuple>() {
                KvinTupleInternal internalTuple;
                ParquetReader<KvinTupleInternal> reader = readers.get(0);
                HashMap<String, Integer> itemPropertyCount = new HashMap<>();
                int propertyCount = 0, readerCount = 0;
                String currentProperty, previousProperty;

                @Override
                public boolean hasNext() {
                    try {
                        if (itemPropertyCount.size() > 0) {
                            // skipping properties if limit is reached
                            if (itemPropertyCount.get(currentProperty) >= limit && limit != 0) {
                                previousProperty = currentProperty;

                                while ((internalTuple = reader.read()) != null) {
                                    propertyCount++;
                                    String property = getProperty(internalTuple);
                                    if (!previousProperty.equals(property)) {
                                        break;
                                    }
                                    previousProperty = property;
                                }
                            }
                        }
                        internalTuple = reader.read();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    if (internalTuple == null && readerCount >= readers.size() - 1) { // terminating condition
                        closeCurrentReader();
                        return false;
                    } else if (internalTuple == null && readerCount <= readers.size() - 1 && itemPropertyCount.get(currentProperty) >= limit && limit != 0) { // moving on to the next reader upon limit reach
                        readerCount++;
                        closeCurrentReader();
                        reader = readers.get(readerCount);
                        return hasNext();
                    } else if (internalTuple == null && readerCount <= readers.size() - 1) { // moving on to the next available reader
                        readerCount++;
                        closeCurrentReader();
                        reader = readers.get(readerCount);
                        try {
                            internalTuple = reader.read();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    return true;
                }

                @Override
                public KvinTuple next() {
                    if (internalTuple != null) {
                        KvinTuple tuple = internalTupleToKvinTuple(internalTuple);
                        propertyCount++;
                        return tuple;
                    } else {
                        return null;
                    }
                }

                private KvinTuple internalTupleToKvinTuple(KvinTupleInternal internalTuple) {
                    Object value = null;
                    if (internalTuple.valueInt != null) {
                        value = internalTuple.valueInt;
                    } else if (internalTuple.valueLong != null) {
                        value = internalTuple.valueLong;
                    } else if (internalTuple.valueFloat != null) {
                        value = internalTuple.valueFloat;
                    } else if (internalTuple.valueDouble != null) {
                        value = internalTuple.valueDouble;
                    } else if (internalTuple.valueString != null) {
                        value = internalTuple.valueString;
                    } else if (internalTuple.valueBool != null) {
                        value = internalTuple.valueBool == 1;
                    } else if (internalTuple.valueObject != null) {
                        value = decodeRecord(internalTuple.valueObject);
                    }

                    // checking for property change
                    String property = getProperty(internalTuple);
                    if (currentProperty == null) {
                        currentProperty = property;
                        previousProperty = currentProperty;
                    } else if (!property.equals(previousProperty)) {
                        currentProperty = property;
                        previousProperty = property;
                        itemPropertyCount.clear();
                    }

                    // updating item property count
                    if (itemPropertyCount.containsKey(property)) {
                        Integer count = itemPropertyCount.get(property) + 1;
                        itemPropertyCount.put(property, count);
                    } else {
                        itemPropertyCount.put(property, 1);
                    }

                    return new KvinTuple(URIs.createURI(idMappings.get("itemMapping").getMappingValue()), URIs.createURI(property), URIs.createURI(idMappings.get("contextMapping").getMappingValue()), internalTuple.time, internalTuple.seqNr, value);

                }

                @Override
                public void close() {
                    try {
                        for (ParquetReader<?> reader : readers) {
                            reader.close();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                public void closeCurrentReader() {
                    try {
                        reader.close();
                    } catch (IOException e) {
                    }
                }
            };
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long delete(URI item, URI property, URI context, long end, long begin) {
        return 0;
    }

    @Override
    public boolean delete(URI item) {
        return false;
    }

    private ArrayList<Path> getFilePath(Map<String, Mapping> idMappings) {

        File archiveFolder = new File(archiveLocation);
        File[] yearWiseFolders = archiveFolder.listFiles();
        ArrayList<Path> matchedFiles = new ArrayList<>();

        Long itemId = idMappings.get("itemMapping").getMappingId();

        // matching ids with relevant parquet files
        if (yearWiseFolders != null) {
            for (File yearFolder : yearWiseFolders) {
                try {
                    String[] folderIdMinMaxData = yearFolder.getName().split("_");
                    if (folderIdMinMaxData[0].contains("metadata")) {
                        continue;
                    }
                    Map<String, Long> yearMinPartitionKey = readPartitionKey(Long.parseLong(folderIdMinMaxData[0]));
                    Map<String, Long> yearMaxPartitionKey = readPartitionKey(Long.parseLong(folderIdMinMaxData[1]));

                    if (itemId >= yearMinPartitionKey.get("itemId") && itemId <= yearMaxPartitionKey.get("itemId")) {
                        for (File weekFolder : new File(yearFolder.getPath()).listFiles()) {
                            try {
                                String[] weekFolderIdMinMaxData = weekFolder.getName().split("_");
                                Map<String, Long> weekMinPartitionKey = readPartitionKey(Long.parseLong(weekFolderIdMinMaxData[0]));
                                Map<String, Long> weekMaxPartitionKey = readPartitionKey(Long.parseLong(weekFolderIdMinMaxData[1]));
                                if (itemId >= weekMinPartitionKey.get("itemId") && itemId <= weekMaxPartitionKey.get("itemId")) {
                                    Path path = new Path(weekFolder.getPath() + "/data.parquet");
                                    if (!matchedFiles.contains(path)) matchedFiles.add(path);
                                    break;
                                }
                            } catch (RuntimeException ignored) {
                            }
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return matchedFiles;
    }

    @Override
    public IExtendedIterator<URI> descendants(URI item) {
        return null;
    }

    @Override
    public IExtendedIterator<URI> descendants(URI item, long limit) {
        return null;
    }

    @Override
    public IExtendedIterator<URI> properties(URI item) {
        try {
            // filters
            Map<String, Mapping> idMappings = getIdMapping(item, null, Kvin.DEFAULT_CONTEXT);
            if (idMappings.size() == 0) {
                return NiceIterator.emptyIterator();
            }
            FilterPredicate filter = generateFetchFilter(idMappings);
            ArrayList<Path> dataFiles = getFilePath(idMappings);
            ArrayList<ParquetReader<KvinTupleInternal>> readers = new ArrayList<>();

            // data readers
            for (Path path : dataFiles) {
                readers.add(AvroParquetReader.<KvinTupleInternal>builder(HadoopInputFile.fromPath(path, new Configuration()))
                        .withDataModel(reflectData)
                        .withFilter(FilterCompat.get(filter))
                        .build());
            }

            return new NiceIterator<>() {
                KvinTupleInternal internalTuple;
                ParquetReader<KvinTupleInternal> reader = readers.get(0);
                int propertyCount = 0, readerCount = 0;

                @Override
                public boolean hasNext() {
                    try {
                        internalTuple = reader.read();
                        if (internalTuple == null && readerCount >= readers.size() - 1) {
                            return false;
                        } else if (internalTuple == null && readerCount <= readers.size() - 1) {
                            readerCount++;
                            reader = readers.get(readerCount);
                            try {
                                internalTuple = reader.read();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return true;
                }

                @Override
                public URI next() {
                    URI property = getKvinTupleProperty();
                    propertyCount++;
                    return property;
                }

                private URI getKvinTupleProperty() {
                    return URIs.createURI("");
                    //return URIs.createURI(internalTuple.getProperty());
                }

                @Override
                public void close() {
                    try {
                        for (ParquetReader<?> reader : readers) {
                            reader.close();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long approximateSize(URI item, URI property, URI context, long end, long begin) {
        return 0;
    }

    @Override
    public void close() {
    }

    // id enum
    enum idType {
        ITEM_ID,
        PROPERTY_ID,
        CONTEXT_ID
    }

    interface Mapping {
        Long getMappingId();

        void setMappingId(Long mappingId);

        String getMappingValue();

        void setMappingValue(String mappingValue);
    }

    static class MappingCacheKeyTuple<T> {
        final T item, property, context;

        MappingCacheKeyTuple(T a, T b, T c) {
            this.item = a;
            this.property = b;
            this.context = c;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MappingCacheKeyTuple<?> that = (MappingCacheKeyTuple<?>) o;
            return Objects.equals(item, that.item) && Objects.equals(property, that.property) && Objects.equals(context, that.context);
        }

        @Override
        public int hashCode() {
            return Objects.hash(item, property, context);
        }
    }

    public static class KvinTupleInternal {
        private byte[] id;
        private Long time;
        private Integer seqNr;
        private Integer valueInt;
        private Long valueLong;
        private Float valueFloat;
        private Double valueDouble;
        private String valueString;
        private Integer valueBool;
        private byte[] valueObject;

        private String archiveLocation;

        public byte[] getId() {
            return id;
        }

        public void setId(byte[] id) {
            this.id = id;
        }

        public long getTime() {
            return time;
        }

        public void setTime(long time) {
            this.time = time;
        }

        public int getSeqNr() {
            return seqNr;
        }

        public void setSeqNr(int seqNr) {
            this.seqNr = seqNr;
        }

        public Integer getValueInt() {
            return valueInt;
        }

        public void setValueInt(Integer valueInt) {
            this.valueInt = valueInt;
        }

        public Long getValueLong() {
            return valueLong;
        }

        public void setValueLong(Long valueLong) {
            this.valueLong = valueLong;
        }

        public Float getValueFloat() {
            return valueFloat;
        }

        public void setValueFloat(Float valueFloat) {
            this.valueFloat = valueFloat;
        }

        public Double getValueDouble() {
            return valueDouble;
        }

        public void setValueDouble(Double valueDouble) {
            this.valueDouble = valueDouble;
        }

        public String getValueString() {
            return valueString;
        }

        public void setValueString(String valueString) {
            this.valueString = valueString;
        }

        public byte[] getValueObject() {
            return valueObject;
        }

        public void setValueObject(byte[] valueObject) {
            this.valueObject = valueObject;
        }

        public Integer getValueBool() {
            return valueBool;
        }

        public void setValueBool(Integer valueBool) {
            this.valueBool = valueBool;
        }
    }

    public static class ItemMapping implements Mapping {
        Long itemId;
        String item;

        public Long getMappingId() {
            return itemId;
        }

        public void setMappingId(Long itemId) {
            this.itemId = itemId;
        }

        public String getMappingValue() {
            return item;
        }

        public void setMappingValue(String item) {
            this.item = item;
        }
    }

    public static class PropertyMapping implements Mapping {
        Long propertyId;
        String property;

        @Override
        public Long getMappingId() {
            return propertyId;
        }

        @Override
        public void setMappingId(Long mappingId) {
            this.propertyId = mappingId;
        }

        @Override
        public String getMappingValue() {
            return property;
        }

        @Override
        public void setMappingValue(String mappingValue) {
            this.property = mappingValue;
        }
    }

    public static class ContextMapping implements Mapping {
        Long contextId;
        String context;

        @Override
        public Long getMappingId() {
            return contextId;
        }

        @Override
        public void setMappingId(Long mappingId) {
            this.contextId = mappingId;
        }

        @Override
        public String getMappingValue() {
            return context;
        }

        @Override
        public void setMappingValue(String mappingValue) {
            this.context = mappingValue;
        }
    }

}
