/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.ai.functions;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;

class TestAnthropicConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(AnthropicConfig.class)
                .setEndpoint(URI.create("https://api.anthropic.com"))
                .setApiKey(null));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = ImmutableMap.<String, String>builder()
                .put("ai.anthropic.endpoint", "https://api.example.com")
                .put("ai.anthropic.api-key", "test-key")
                .buildOrThrow();

        AnthropicConfig expected = new AnthropicConfig()
                .setEndpoint(URI.create("https://api.example.com"))
                        .setApiKey("test-key");

        assertFullMapping(properties, expected);
    }
}
