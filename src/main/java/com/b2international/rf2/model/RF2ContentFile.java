/*
 * Copyright 2019 B2i Healthcare Pte Ltd, http://b2i.sg
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
package com.b2international.rf2.model;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.b2international.rf2.RF2CreateContext;
import com.b2international.rf2.check.RF2IssueAcceptor;
import com.b2international.rf2.naming.RF2FileName;
import com.b2international.rf2.naming.file.RF2ContentSubType;
import com.b2international.rf2.naming.file.RF2ContentType;
import com.b2international.rf2.validation.RF2ColumnValidator;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;

/**
 * @since 0.1
 */
public abstract class RF2ContentFile extends RF2File {

	private String[] header;
	
	public RF2ContentFile(Path parent, RF2FileName fileName) {
		super(parent, fileName);
	}
	
	@Override
	public String getType() {
		return getFileName()
				.getElement(RF2ContentType.class)
				.map(RF2ContentType::getContentType)
				// TODO recognize type from header
				.orElse("Unknown");
	}
	
	@Override
	public void visit(Consumer<RF2File> visitor) {
		visitor.accept(this);
	}
	
	public RF2ContentSubType getReleaseType() {
		return getFileName()
				.getElement(RF2ContentSubType.class)
				.orElse(null);
	}
	
	@Override
	public void check(RF2IssueAcceptor acceptor) throws IOException {
		super.check(acceptor);
		// check RF2 header
		final String[] rf2HeaderSpec = getRF2HeaderSpec();
		final String[] actualHeader = getHeader();
		if (!Arrays.equals(rf2HeaderSpec, actualHeader)) {
			// TODO report incorrect header columns
			acceptor.error("Header does not conform to specification");
			return;
		}

		// assign validators to RF2 columns
		final String[] header = getHeader();
		final Map<Integer, RF2ColumnValidator> validatorsByIndex = new HashMap<>(header.length);
		for (int i = 0; i < header.length; i++) {
			final String columnHeader = header[i];
			final RF2ColumnValidator validator = RF2ColumnValidator.VALIDATORS.get(columnHeader);
			if (validator != null) {
				validatorsByIndex.put(i, validator);
			} else {
				acceptor.warn("No validator is registered for column header '%s'.", columnHeader);
				validatorsByIndex.put(i, RF2ColumnValidator.NOOP);
			}
		}
		
		// validate each row in RF2 content file
		rowsParallel().forEach(row -> {
			for (int i = 0; i < row.length; i++) {
				validatorsByIndex.get(i).check(this, header[i], row[i], acceptor);
			}
		});
	}
	
	@Override
	public void create(RF2CreateContext context) throws IOException {
		context.log().log("Creating '%s'...", getPath());
		
		final RF2ContentSubType releaseType = getReleaseType();
		try (BufferedWriter writer = Files.newBufferedWriter(getPath(), StandardOpenOption.CREATE_NEW)) {
			writer.write(newLine(getHeader()));
			
			final Map<String, Map<String, String>> componentsByIdEffectiveTime = new HashMap<>(); 

			context.visitSourceRows(getType(), getHeader(), /* parallel if */ releaseType.isSnapshot(), line -> {
				try {
					// if type specific filter filters it out, then skip line from the source files
					if (!filter(line)) {
						return;
					}
					
					String id = line[0];
					String effectiveTime = line[1];
					String rawLine = newLine(line);
					String lineHash = Hashing.sha256().hashString(rawLine, StandardCharsets.UTF_8).toString();
					
					if (componentsByIdEffectiveTime.containsKey(id) && componentsByIdEffectiveTime.get(id).containsKey(effectiveTime)) {
						// log a warning about inconsistent ID-EffectiveTime content, keep the first occurrence of the line and skip the others
						if (!lineHash.equals(componentsByIdEffectiveTime.get(id).get(effectiveTime))) {
							context.log().warn("Skipping duplicate RF2 line found with same '%s' ID in '%s' effectiveTime but with different column values.", id, effectiveTime);
						}
						return;
					}
					
					if (releaseType.isFull()) {
						// in case of Full we can immediately write it out
						if (!componentsByIdEffectiveTime.containsKey(id)) {
							componentsByIdEffectiveTime.put(id, new HashMap<>());
						}
						componentsByIdEffectiveTime.get(id).put(effectiveTime, lineHash);
						writer.write(rawLine);
					} else if (releaseType.isSnapshot()) {
						// in case of Snapshot we check that the current effective time is greater than the currently registered and replace if yes
						if (componentsByIdEffectiveTime.containsKey(id)) {
							Entry<String, String> effectiveTimeHash = Iterables.getOnlyElement(componentsByIdEffectiveTime.get(id).entrySet());
							if (effectiveTime.isEmpty() || effectiveTime.compareTo(effectiveTimeHash.getKey()) > 0) {
								componentsByIdEffectiveTime.put(id, Map.of(effectiveTime, lineHash));
							}
						} else {
							componentsByIdEffectiveTime.put(id, Map.of(effectiveTime, lineHash));	
						}
					} else if (releaseType.isDelta()) {
						// in case of Delta we will only add the lines with the releaseDate effective time
						// TODO support closest to specified releaseDate!!!
						if (context.getReleaseDate().equals(effectiveTime)) {
							componentsByIdEffectiveTime.put(id, Map.of(effectiveTime, lineHash));
							writer.write(rawLine);
						}
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			
			// Snapshot needs a second run, since we just extracted the applicable rows from all source files, and we need to actually write them into the output
			if (releaseType.isSnapshot()) {
				context.visitSourceRows(getType(), getHeader(), false, line -> {
					try {
						// if type specific filter filters it out, then skip line from the source files
						if (!filter(line)) {
							return;
						}
						
						String id = line[0];
						String effectiveTime = line[1];
						if (componentsByIdEffectiveTime.containsKey(id) && componentsByIdEffectiveTime.get(id).containsKey(effectiveTime)) {
							// remove the item from the id effective time map to indicate that we wrote it out
							componentsByIdEffectiveTime.remove(id);
							writer.write(newLine(line));
						}
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
			}
		}
	}
	
	/**
	 * Subtypes may optionally filter out RF2 lines from the output files. 
	 * @param line
	 * @return
	 * @see RF2RelationshipFile
	 */
	protected boolean filter(String[] line) {
		return true;
	}

	protected final String newLine(String[] values) {
		return String.format("%s%s", String.join(TAB, values), CRLF);
	}

	/**
	 * @return the current RF2 header by reading the first line of the file or if this is a non-existing file returns the header from the spec for kind of RF2 files
	 * @throws IOException 
	 */
	public final String[] getHeader() throws IOException {
		if (header == null) {
			if (Files.exists(getPath())) {
				header = Files.lines(getPath()).findFirst().orElse("N/A").split(TAB);
			} else {
				header = getRF2HeaderSpec();
			}
		}
		return header;
	}

	/**
	 * @return the RF2 specification header from the {@link RF2Header} l
	 */
	protected abstract String[] getRF2HeaderSpec();
	
	/**
	 * @return the actual raw data from this RF2 content file without header and each line converted into String[] objects in a sequential stream.
	 * @throws IOException
	 */
	public final Stream<String[]> rows() throws IOException {
		return Files.lines(getPath())
				.skip(1)
				.map(line -> line.split(TAB));
	}
	
	/**
	 * @return the actual raw data from this RF2 content file without header and each line converted into String[] objects in a parallel stream.
	 * @throws IOException
	 */
	public final Stream<String[]> rowsParallel() throws IOException {
		return Files.lines(getPath())
				.skip(1)
				.parallel()
				.map(line -> line.split(TAB));
	}

}
