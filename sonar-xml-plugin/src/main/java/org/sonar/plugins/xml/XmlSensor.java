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

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.ParserConfigurationException;
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
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.xml.checks.AbstractXmlCheck;
import org.sonar.plugins.xml.checks.CheckRepository;
import org.sonar.plugins.xml.checks.ParsingErrorCheck;
import org.sonar.plugins.xml.checks.XmlFile;
import org.sonar.plugins.xml.checks.XmlIssue;
import org.sonar.plugins.xml.checks.XmlSourceCode;
import org.sonar.plugins.xml.highlighting.HighlightingData;
import org.sonar.plugins.xml.highlighting.XMLHighlighting;
import org.sonar.plugins.xml.language.Xml;
import org.sonar.plugins.xml.newparser.NewXmlFile;
import org.sonar.plugins.xml.parsers.ParseException;

public class XmlSensor implements Sensor {

  private static final Logger LOG = Loggers.get(XmlSensor.class);

  private final Checks<Object> checks;
  private final FileSystem fileSystem;
  private final FilePredicate mainFilesPredicate;
  private final FileLinesContextFactory fileLinesContextFactory;

  public XmlSensor(FileSystem fileSystem, CheckFactory checkFactory, FileLinesContextFactory fileLinesContextFactory) {
    this.fileLinesContextFactory = fileLinesContextFactory;
    this.checks = checkFactory.create(CheckRepository.REPOSITORY_KEY).addAnnotatedChecks((Iterable<?>) CheckRepository.getCheckClasses());
    this.fileSystem = fileSystem;
    this.mainFilesPredicate = fileSystem.predicates().and(
      fileSystem.predicates().hasType(InputFile.Type.MAIN),
      fileSystem.predicates().hasLanguage(Xml.KEY));
  }

  private void computeLinesMeasures(SensorContext context, XmlFile xmlFile) {
    LineCounter.analyse(context, fileLinesContextFactory, xmlFile);
  }

  private void runChecks(SensorContext context, XmlFile xmlFile) {
    XmlSourceCode sourceCode = new XmlSourceCode(xmlFile);

    // Do not execute any XML rule when an XML file is corrupted (SONARXML-13)
    if (sourceCode.parseSource()) {
      for (Object check : checks.all()) {
        ((AbstractXmlCheck) check).setRuleKey(checks.ruleKey(check));
        ((AbstractXmlCheck) check).validate(sourceCode);
      }
      saveIssue(context, sourceCode);
      InputFile inputFile = xmlFile.getInputFile();
      try {
        saveSyntaxHighlighting(context, XMLHighlighting.highlight(NewXmlFile.create(inputFile)), inputFile);
      } catch (ParserConfigurationException | IOException e) {
        LOG.warn(String.format("Can't highlight following file : %s", inputFile.uri()), e);
      }
    }
  }

  private static void saveSyntaxHighlighting(SensorContext context, List<HighlightingData> highlightingDataList, InputFile inputFile) {
    NewHighlighting highlighting = context.newHighlighting().onFile(inputFile);

    for (HighlightingData highlightingData : highlightingDataList) {
      highlightingData.highlight(highlighting);
    }
    highlighting.save();
  }

  protected void saveIssue(SensorContext context, XmlSourceCode sourceCode) {
    for (XmlIssue xmlIssue : sourceCode.getXmlIssues()) {
      NewIssue newIssue = context.newIssue().forRule(xmlIssue.getRuleKey());
      NewIssueLocation location = newIssue.newLocation()
        .on(sourceCode.getInputFile())
        .message(xmlIssue.getMessage());
      if (xmlIssue.getLine() != null) {
        location.at(sourceCode.getInputFile().selectLine(xmlIssue.getLine()));
      }
      newIssue.at(location).save();
    }
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

  @Override
  public void execute(SensorContext context) {
    Optional<RuleKey> parsingErrorKey = getParsingErrorKey();

    for (InputFile inputFile : fileSystem.inputFiles(mainFilesPredicate)) {
      XmlFile xmlFile = new XmlFile(inputFile, fileSystem);
      try {
        computeLinesMeasures(context, xmlFile);
        runChecks(context, xmlFile);
      } catch (ParseException e) {
        processParseException(e, context, inputFile, parsingErrorKey);
      } catch (RuntimeException e) {
        processException(e, context, inputFile);
      }
    }
  }

  private Optional<RuleKey> getParsingErrorKey() {
    for (Object obj : checks.all()) {
      AbstractXmlCheck check = (AbstractXmlCheck) obj;
      if (check instanceof ParsingErrorCheck) {
        return Optional.of(checks.ruleKey(check));
      }
    }
    return Optional.empty();
  }

  private static void processParseException(ParseException e, SensorContext context, InputFile inputFile, Optional<RuleKey> parsingErrorKey) {
    reportAnalysisError(e, context, inputFile);

    LOG.warn("Unable to parse file {}", inputFile.uri());
    LOG.warn("Cause: {}", e.getMessage());

    if (parsingErrorKey.isPresent()) {
      // the ParsingErrorCheck rule is activated: we create a beautiful issue
      NewIssue newIssue = context.newIssue();
      NewIssueLocation primaryLocation = newIssue.newLocation()
        .message("Parse error: " + e.getMessage())
        .on(inputFile);
      newIssue
        .forRule(parsingErrorKey.get())
        .at(primaryLocation)
        .save();
    }
  }

  private static void processException(RuntimeException e, SensorContext context, InputFile inputFile) {
    reportAnalysisError(e, context, inputFile);

    throw new IllegalStateException("Unable to analyse file " + inputFile.uri(), e);
  }

  private static void reportAnalysisError(RuntimeException e, SensorContext context, InputFile inputFile) {
    context.newAnalysisError()
      .onFile(inputFile)
      .message(e.getMessage())
      .save();
  }

}
