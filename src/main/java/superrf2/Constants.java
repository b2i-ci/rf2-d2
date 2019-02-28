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
package superrf2;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * @since 0.1
 */
public final class Constants {

	public static final Charset UTF8 = StandardCharsets.UTF_8;
	public static final String TXT = "txt";
	public static final String ZIP = "zip";
	public static final String ACCEPTED_FILES = String.join(",", TXT, ZIP);
	
}
