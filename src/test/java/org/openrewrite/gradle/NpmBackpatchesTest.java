/*
 * Copyright 2026 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.SortedMap;

import static org.assertj.core.api.Assertions.assertThat;

class NpmBackpatchesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void anInstalledVersionTakesTheNewestBackpatchOnItsLine() throws Exception {
        //language=json
        SortedMap<String, String> fixes = NpmBackpatches.index(JSON.readTree("""
          {"newest": {"lib": {"1.2.3": "1.2.4-backpatch-00001", "1.4.0": "1.4.1-backpatch-00002",
                              "2.0.0": "2.0.1-backpatch-00001"}}}
          """)).get("lib");

        assertThat(NpmBackpatches.backpatchFor(fixes, "1.2.0")).isEqualTo("1.4.1-backpatch-00002");
        assertThat(NpmBackpatches.backpatchFor(fixes, "1.4.0")).isEqualTo("1.4.1-backpatch-00002");
        assertThat(NpmBackpatches.backpatchFor(fixes, "2.0.0")).isEqualTo("2.0.1-backpatch-00001");
        // Later than every version fixed on its line, on another line, or a backpatch itself
        assertThat(NpmBackpatches.backpatchFor(fixes, "1.5.0")).isNull();
        assertThat(NpmBackpatches.backpatchFor(fixes, "3.0.0")).isNull();
        assertThat(NpmBackpatches.backpatchFor(fixes, "1.4.1-backpatch-00002")).isNull();
    }

    @Test
    void aZeroVersionsLineIsItsMinor() throws Exception {
        //language=json
        SortedMap<String, String> fixes = NpmBackpatches.index(JSON.readTree("""
          {"newest": {"lib": {"0.3.1": "0.3.2-backpatch-00001"}}}
          """)).get("lib");

        assertThat(NpmBackpatches.backpatchFor(fixes, "0.3.0")).isEqualTo("0.3.2-backpatch-00001");
        assertThat(NpmBackpatches.backpatchFor(fixes, "0.2.9")).isNull();
    }

    @Test
    void bundlesWhatLeadsToABackpatchButNeverOurOwnPackagesPeersOrWhatIsBundledAlready() throws Exception {
        //language=json
        Map<String, SortedMap<String, String>> index = NpmBackpatches.index(JSON.readTree("""
          {"newest": {"lib": {"1.0.0": "1.0.1-backpatch-00001"}, "util": {"2.1.0": "2.1.1-backpatch-00001"}}}
          """));
        //language=json
        JsonNode lockfile = JSON.readTree("""
          {
            "lockfileVersion": 3,
            "packages": {
              "": {
                "name": "pkg",
                "dependencies": {"lib": "^1.0.0", "wrapper": "^1.0.0", "deep": "^1.0.0", "other": "^1.0.0",
                                 "@openrewrite/rewrite": "^8.0.0"},
                "devDependencies": {"tool": "^1.0.0"},
                "peerDependencies": {"peer": "^1.0.0"}
              },
              "node_modules/lib": {"version": "1.0.0"},
              "node_modules/wrapper": {"version": "1.0.0", "dependencies": {"lib": "^1.0.0"}},
              "node_modules/deep": {"version": "1.0.0", "dependencies": {"util": "^2.0.0"}},
              "node_modules/deep/node_modules/util": {"version": "2.0.5"},
              "node_modules/other": {"version": "1.0.0", "dependencies": {"lib": "^1.0.0"}},
              "node_modules/other/node_modules/lib": {"version": "1.0.0", "inBundle": true},
              "node_modules/@openrewrite/rewrite": {"version": "8.0.0", "dependencies": {"util": "^2.0.0"}},
              "node_modules/util": {"version": "2.0.5"},
              "node_modules/tool": {"version": "1.0.0", "dev": true, "dependencies": {"util": "^2.0.0"}},
              "node_modules/peer": {"version": "1.0.0", "peer": true, "dependencies": {"lib": "^1.0.0"}}
            }
          }
          """);

        NpmBackpatches.Plan plan = NpmBackpatches.plan(lockfile, index, name -> name.startsWith("@openrewrite/"));

        assertThat(plan.direct()).containsExactly(Map.entry("lib", "1.0.1-backpatch-00001"));
        assertThat(plan.swaps()).containsExactly(
          Map.entry("node_modules/deep/node_modules/util", "2.1.1-backpatch-00001"),
          Map.entry("node_modules/lib", "1.0.1-backpatch-00001"));
        // wrapper resolves lib from the root, so it is bundled to take the bundled lib. other bundles its own lib, the
        // hoisted util is only @openrewrite/rewrite's and the tool's, and peer is the installer's.
        assertThat(plan.bundled()).containsExactly("deep", "lib", "wrapper");
        assertThat(plan.fixed()).containsExactly("lib 1.0.0 → 1.0.1-backpatch-00001", "util 2.0.5 → 2.1.1-backpatch-00001");
    }

    @Test
    void nothingToBundleWhenNoProductionDependencyTakesABackpatch() throws Exception {
        //language=json
        Map<String, SortedMap<String, String>> index = NpmBackpatches.index(JSON.readTree("""
          {"newest": {"lib": {"1.0.0": "1.0.1-backpatch-00001"}}}
          """));
        //language=json
        JsonNode lockfile = JSON.readTree("""
          {
            "lockfileVersion": 3,
            "packages": {
              "": {"name": "pkg", "dependencies": {"other": "^1.0.0"}, "devDependencies": {"lib": "^1.0.0"}},
              "node_modules/other": {"version": "1.0.0"},
              "node_modules/lib": {"version": "1.0.0", "dev": true}
            }
          }
          """);

        assertThat(NpmBackpatches.plan(lockfile, index, name -> false).isEmpty()).isTrue();
    }

    @Test
    void theAlliancesTarballOfAScopedPackage() {
        assertThat(NpmBackpatches.tarball("https://backpatch.moderne.io/npm/", "@scope/name", "1.0.1-backpatch-00001"))
          .isEqualTo("https://backpatch.moderne.io/npm/@scope/name/-/name-1.0.1-backpatch-00001.tgz");
        assertThat(NpmBackpatches.tarball("https://backpatch.moderne.io/npm/", "picomatch", "4.0.8-backpatch-00001"))
          .isEqualTo("https://backpatch.moderne.io/npm/picomatch/-/picomatch-4.0.8-backpatch-00001.tgz");
    }
}
