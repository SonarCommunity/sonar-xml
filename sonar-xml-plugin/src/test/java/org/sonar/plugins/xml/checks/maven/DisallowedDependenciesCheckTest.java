/*
 * SonarQube XML Plugin
 * Copyright (C) 2010-2022 SonarSource SA
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.sonarsource.analyzer.commons.xml.checks.SonarXmlCheckVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisallowedDependenciesCheckTest {

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
  void should_fail_with_invalid_name_provided() {
    check.dependencyName = "org.sonar";
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> SonarXmlCheckVerifier.verifyIssues("noVersion/pom.xml", check));
    assertThat(e.getMessage()).isEqualTo("[S3417] Unable to build matchers from provided dependency name: org.sonar");
  }

  @Test
  void should_fail_with_invalid_version_provided() {
    check.dependencyName = "org.sonar.*:*";
    check.version = "version-0";
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
      () -> SonarXmlCheckVerifier.verifyIssues("noVersion/pom.xml", check));
    assertThat(e.getMessage()).isEqualTo("[S3417] Unable to build matchers from provided dependency name: org.sonar.*:*");
  }
}
