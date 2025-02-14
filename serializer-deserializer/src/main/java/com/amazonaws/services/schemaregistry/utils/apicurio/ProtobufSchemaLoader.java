/*
 * Copyright 2021 Red Hat
 * Portions Copyright 2020 Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This will be removed once Apicurio releases the latest version with the json_name fix
 * https://github.com/Apicurio/apicurio-registry/blob/master/utils/protobuf-schema-utilities/src/main/java/io/apicurio/registry/utils/protobuf/schema/ProtobufSchemaLoader.java
 */

package com.amazonaws.services.schemaregistry.utils.apicurio;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Feature;
import com.google.common.jimfs.Jimfs;
import com.google.common.jimfs.PathType;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.ProtoFile;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class ProtobufSchemaLoader {

    private static final String GOOGLE_API_PATH = "google/type/";
    //Adding pre-built support for commonly used Google API Protos,
    //https://github.com/googleapis/googleapis
    //These files need to be manually loaded into the FileSystem
    //as Square doesn't support them by default.
    private final static Set<String> GOOGLE_API_PROTOS =
            ImmutableSet.<String>builder()
                    .add("money.proto")
                    .add("timeofday.proto")
                    .add("date.proto")
                    .add("calendar_period.proto")
                    .add("color.proto")
                    .add("dayofweek.proto")
                    .add("latlng.proto")
                    .add("fraction.proto")
                    .add("month.proto")
                    .add("phone_number.proto")
                    .add("postal_address.proto")
                    .add("localized_text.proto")
                    .add("interval.proto")
                    .add("expr.proto")
                    .add("quaternion.proto")
                    .build();

    private static FileSystem getFileSystem() throws IOException {
        final FileSystem inMemoryFileSystem =
                Jimfs.newFileSystem(
                        Configuration.builder(PathType.unix())
                                .setRoots("/")
                                .setWorkingDirectory("/")
                                .setAttributeViews("basic")
                                .setSupportedFeatures(Feature.SYMBOLIC_LINKS)
                                .build());

        createDirectory(GOOGLE_API_PATH.split("/"), inMemoryFileSystem);

        final ClassLoader classLoader = ProtobufSchemaLoader.class.getClassLoader();
        for (String googleApiProto : GOOGLE_API_PROTOS) {
            //Loads the proto file resource files.
            final InputStream inputStream = classLoader.getResourceAsStream(GOOGLE_API_PATH + googleApiProto);
            final String fileContents = CharStreams.toString(new InputStreamReader(inputStream, Charsets.UTF_8));
            final Path googleApiProtoPath = inMemoryFileSystem.getPath("/", GOOGLE_API_PATH, googleApiProto);
            Files.write(googleApiProtoPath, fileContents.getBytes());
        }
        return inMemoryFileSystem;
    }

    private static String createDirectory(String[] dirs, FileSystem fileSystem) throws IOException {
        String dirPath = "";
        for (String dir: dirs) {
            dirPath = dirPath + "/" + dir;
            Path path = fileSystem.getPath(dirPath);
            Files.createDirectory(path);
        }

        return dirPath;
    }

    /**
     * Creates a schema loader using a in-memory file system. This is required for square wire schema parser and linker
     * to load the types correctly. See https://github.com/square/wire/issues/2024#
     * As of now this only supports reading one .proto file but can be extended to support reading multiple files.
     * @param packageName Package name for the .proto if present
     * @param fileName Name of the .proto file.
     * @param schemaDefinition Schema Definition to parse.
     * @return Schema - parsed and properly linked Schema.
     */
    public static ProtobufSchemaLoaderContext loadSchema(Optional<String> packageName, String fileName, String schemaDefinition)
            throws IOException {
        final FileSystem inMemoryFileSystem = getFileSystem();

        String [] dirs = {};
        if (packageName.isPresent()) {
            dirs = packageName.get().split("\\.");
        }
        String protoFileName = fileName.endsWith(".proto") ? fileName : fileName + ".proto";
        try {
            String dirPath = createDirectory(dirs, inMemoryFileSystem);
            Path path = inMemoryFileSystem.getPath(dirPath, protoFileName);
            Files.write(path, schemaDefinition.getBytes());

            try (SchemaLoader schemaLoader = new SchemaLoader(inMemoryFileSystem)) {
                schemaLoader.initRoots(Lists.newArrayList(Location.get("/")), Lists.newArrayList(Location.get("/")));

                Schema schema = schemaLoader.loadSchema();
                ProtoFile protoFile = schema.protoFile(path.toString().replaceFirst("/", ""));

                if (protoFile == null) {
                    throw new RuntimeException("Error loading Protobuf File: " + protoFileName);
                }

                return new ProtobufSchemaLoaderContext(schema, protoFile);
            }
        } catch (Exception e) {
            throw e;
        } finally {
            inMemoryFileSystem.close();
        }
    }

    protected static class ProtobufSchemaLoaderContext {
        private final Schema schema;
        private final ProtoFile protoFile;

        public Schema getSchema() {
            return schema;
        }

        public ProtoFile getProtoFile() {
            return protoFile;
        }

        public ProtobufSchemaLoaderContext(Schema schema, ProtoFile protoFile) {
            this.schema = schema;
            this.protoFile = protoFile;
        }
    }
}
