/*
 * SonarQube XML Plugin
 * Copyright (C) 2010-2018 SonarSource SA
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
package org.sonar.plugins.xml;

import java.net.URI;
import java.util.List;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.rule.Checks;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.highlighting.NewHighlighting;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.internal.google.common.annotations.VisibleForTesting;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.xml.highlighting.HighlightingData;
import org.sonar.plugins.xml.highlighting.XMLHighlighting;
import org.sonar.plugins.xml.language.Xml;
import org.sonar.plugins.xml.newchecks.NewXmlCheckList;
import org.sonar.plugins.xml.newchecks.ParsingErrorCheck;
import org.sonar.plugins.xml.newparser.NewXmlFile;
import org.sonar.plugins.xml.newparser.checks.NewXmlCheck;

public class XmlSensor implements Sensor {

  private static final Logger LOG = Loggers.get(XmlSensor.class);

  private static final RuleKey PARSING_ERROR_RULE_KEY = RuleKey.of(Xml.REPOSITORY_KEY, ParsingErrorCheck.RULE_KEY);

  private final Checks<Object> checks;
  private final boolean parsingErrorCheckEnabled;
  private final FileSystem fileSystem;
  private final FilePredicate mainFilesPredicate;
  private final FileLinesContextFactory fileLinesContextFactory;

  public XmlSensor(FileSystem fileSystem, CheckFactory checkFactory, FileLinesContextFactory fileLinesContextFactory) {
    this.fileLinesContextFactory = fileLinesContextFactory;
    this.checks = checkFactory.create(Xml.REPOSITORY_KEY).addAnnotatedChecks((Iterable<?>) NewXmlCheckList.getCheckClasses());
    this.parsingErrorCheckEnabled = this.checks.of(PARSING_ERROR_RULE_KEY) != null;
    this.fileSystem = fileSystem;
    this.mainFilesPredicate = fileSystem.predicates().and(
      fileSystem.predicates().hasType(InputFile.Type.MAIN),
      fileSystem.predicates().hasLanguage(Xml.KEY));
  }

  @Override
  public void execute(SensorContext context) {

    for (InputFile inputFile : fileSystem.inputFiles(mainFilesPredicate)) {
      try {
        NewXmlFile newXmlFile = NewXmlFile.create(inputFile);
        LineCounter.analyse(context, fileLinesContextFactory, newXmlFile);
        runChecks(context, newXmlFile);
        saveSyntaxHighlighting(context, XMLHighlighting.highlight(newXmlFile), inputFile);
      } catch (Exception e) {
        processParseException(e, context, inputFile);
      }
    }
  }

  private void runChecks(SensorContext context, NewXmlFile newXmlFile) {
    checks.all().stream()
      .filter(NewXmlCheck.class::isInstance)
      .map(NewXmlCheck.class::cast)
      // checks.ruleKey(check) is never null because "check" is part of "checks.all()"
      .forEach(check -> runCheck(context, check, checks.ruleKey(check), newXmlFile));
  }

  @VisibleForTesting
  void runCheck(SensorContext context, NewXmlCheck check, RuleKey ruleKey, NewXmlFile newXmlFile) {
    try {
      check.scanFile(context, ruleKey, newXmlFile);
    } catch (Exception e) {
      logFailingRule(ruleKey, newXmlFile.getInputFile().uri(), e);
    }
  }

  private static void logFailingRule(RuleKey rule, URI fileLocation, Exception e) {
    LOG.error(String.format("Unable to execute rule %s on %s", rule, fileLocation), e);
  }

  private static void saveSyntaxHighlighting(SensorContext context, List<HighlightingData> highlightingDataList, InputFile inputFile) {
    NewHighlighting highlighting = context.newHighlighting().onFile(inputFile);

    for (HighlightingData highlightingData : highlightingDataList) {
      highlightingData.highlight(highlighting);
    }
    highlighting.save();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .onlyOnLanguage(Xml.KEY)
      .name("XML Sensor");
  }

  private void processParseException(Exception e, SensorContext context, InputFile inputFile) {
    reportAnalysisError(e, context, inputFile);

    LOG.warn(String.format("Unable to analyse file %s;", inputFile.uri()));
    LOG.debug("Cause: {}", e.getMessage());

    if (parsingErrorCheckEnabled) {
      createParsingErrorIssue(e, context, inputFile);
    }
  }

  private static void reportAnalysisError(Exception e, SensorContext context, InputFile inputFile) {
    context.newAnalysisError()
      .onFile(inputFile)
      .message(e.getMessage())
      .save();
  }

  private static void createParsingErrorIssue(Exception e, SensorContext context, InputFile inputFile) {
    NewIssue newIssue = context.newIssue();
    NewIssueLocation primaryLocation = newIssue.newLocation()
      .message("Parse error: " + e.getMessage())
      .on(inputFile);
    newIssue
      .forRule(PARSING_ERROR_RULE_KEY)
      .at(primaryLocation)
      .save();
  }

}
