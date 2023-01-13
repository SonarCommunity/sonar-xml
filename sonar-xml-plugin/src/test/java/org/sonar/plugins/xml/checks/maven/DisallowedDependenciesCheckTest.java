/*
 * SonarQube XML Plugin
 * Copyright (C) 2010-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.xml.checks.maven;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sonar.api.utils.log.LogTesterJUnit5;
import org.sonar.api.utils.log.LoggerLevel;
import org.sonarsource.analyzer.commons.xml.checks.SonarXmlCheckVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class DisallowedDependenciesCheckTest {

  @RegisterExtension
  public LogTesterJUnit5 logTester = new LogTesterJUnit5();

  private DisallowedDependenciesCheck check;

  @BeforeEach
  public void setup() {
    check = new DisallowedDependenciesCheck();
  }

  @Test
  void without_version() {
    check.dependencyName = "*:log4j";
    SonarXmlCheckVerifier.verifyIssues("noVersion/pom.xml", check);
  }

  @ParameterizedTest
  @CsvSource({
          "1.2.*,regexVersion/pom.xml",
          "1.1.0-1.2.15,rangeVersion/pom.xml",
          "1.1.0-1.2.15,propertyVersion/pom.xml",
  })
  void with_versions(String version, String filePath) {
    check.dependencyName = "*:log4j";
    check.version = version;
    SonarXmlCheckVerifier.verifyIssues(filePath, check);
  }

  @Test
  void should_log_error_when_invalid_dependency_name_is_provided() {
    check.dependencyName = "org.sonar";
    check.version = "";
    SonarXmlCheckVerifier.verifyNoIssue("noVersion/pom.xml", check);
    assertThat(logTester.logs(LoggerLevel.ERROR))
      .containsExactly("The rule xml:S3417 is configured with some invalid parameters." +
        " Invalid DependencyName pattern 'org.sonar'." +
        " Should match '[groupId]:[artifactId]', you can use '*' as wildcard or a regular expression." +
        " Error: Missing ':' separator.");
  }

  @Test
  void should_log_error_when_invalid_dependency_version_is_provided() {
    check.dependencyName = "org.sonar.*:*";
    check.version = "version-0";
    SonarXmlCheckVerifier.verifyNoIssue("noVersion/pom.xml", check);
    assertThat(logTester.logs(LoggerLevel.ERROR))
      .containsExactly("The rule xml:S3417 is configured with some invalid parameters." +
        " Invalid Version pattern 'version-0'." +
        " Leave blank for all versions. You can use '*' as wildcard and '-' as range like '1.0-3.1' or '*-3.1'." +
        " Error: Invalid version range lower bound  'version'." +
        " Unsupported version format 'version'." +
        " The version does not match expected pattern: '<major version>.<minor version>.<incremental version>'");
  }

}
