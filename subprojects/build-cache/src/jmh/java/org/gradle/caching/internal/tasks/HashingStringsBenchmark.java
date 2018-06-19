/*
 * Copyright 2018 the original author or authors.
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
 */

package org.gradle.caching.internal.tasks;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@SuppressWarnings("Since15")
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 7)
@State(Scope.Benchmark)
public class HashingStringsBenchmark {

    public static final byte[] PATH_SEPARATOR_BYTES = File.pathSeparator.getBytes();
    private String absolutePath;
    private String[] segments;
    private Path tempDirectory;
    private ByteBuffer out = ByteBuffer.allocate(1024);
    private ByteBuffer directOut = ByteBuffer.allocateDirect(1024);
    private CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();

    @Setup(Level.Trial)
    public void setupTrial() throws IOException {
        tempDirectory = Files.createTempDirectory("hashing-trial-");
        Path subdir = tempDirectory.resolve("some/long/sub/dir/to/get/a/long/string");
        absolutePath = subdir.toAbsolutePath().toString();
        segments = StringUtils.split(absolutePath, File.separatorChar);
    }

    @Benchmark
    public void hashAbsolutePath() {
        Hasher hasher = Hashing.md5().newHasher();
        hasher.putString(absolutePath);
        hasher.hash();
    }

    @Benchmark
    public void hashAbsolutePathViaEncoder() {
        Hasher hasher = Hashing.md5().newHasher();
        encoder.reset();
        CharBuffer in = CharBuffer.wrap(absolutePath);
        out.position(0);
        while (true) {
            CoderResult result = encoder.encode(in, out, true);
            hasher.putBytes(out.array(), 0, out.position());
            out.position(0);
            if (!result.isOverflow()) {
                break;
            }
        }
        encoder.flush(out);
        hasher.putBytes(out.array(), 0, out.position());
        hasher.hash();
    }

    @Benchmark
    public void hashAbsolutePathViaEncoderUsingDirectBuffer() {
        Hasher hasher = Hashing.md5().newHasher();
        encoder.reset();
        CharBuffer in = CharBuffer.wrap(absolutePath);
        directOut.position(0);
        hashStringViaEncoderDirect(hasher, in, directOut, encoder);
        hasher.hash();
    }

    private static void hashStringViaEncoderDirect(Hasher hasher, CharBuffer in, ByteBuffer directOut, CharsetEncoder encoder) {
        while (true) {
            CoderResult result = encoder.encode(in, directOut, true);
            for (int i = 0; i < directOut.position(); i++) {
                hasher.putByte(directOut.get(i));
            }
            directOut.position(0);
            if (!result.isOverflow()) {
                break;
            }
        }
        encoder.flush(directOut);
        for (int i = 0; i < directOut.position(); i++) {
            hasher.putByte(directOut.get(i));
        }
    }

    @Benchmark
    public void hashPathSegments() {
        Hasher hasher = Hashing.md5().newHasher();
        for (String segment : segments) {
            hasher.putString(segment);
            hasher.putBytes(PATH_SEPARATOR_BYTES);
        }
        hasher.hash();
    }

    @Benchmark
    public void hashPathSegmentsViaEncoder() {
        Hasher hasher = Hashing.md5().newHasher();
        encoder.reset();
        for (String segment : segments) {
            CharBuffer in = CharBuffer.wrap(segment);
            while (true) {
                CoderResult result = encoder.encode(in, out, false);
                hasher.putBytes(out.array(), 0, out.position());
                out.position(0);
                if (!result.isOverflow()) {
                    break;
                }
            }
            hasher.putBytes(PATH_SEPARATOR_BYTES);
        }
        while (true) {
            CoderResult result = encoder.encode(CharBuffer.wrap(""), out, true);
            hasher.putBytes(out.array(), 0, out.position());
            out.position(0);
            if (!result.isOverflow()) {
                break;
            }
        }
        while (true) {
            CoderResult result = encoder.flush(out);
            hasher.putBytes(out.array(), 0, out.position());
            out.position(0);
            if (!result.isOverflow()) {
                break;
            }
        }

        hasher.hash();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        FileUtils.forceDelete(tempDirectory.toFile());
    }
}
