// CodeNarc ruleset for hubitat-flair-vents (task 1.3).
//
// Purpose: a npm-free, Gradle-native reproduction of the lint gate. The frozen
// warning baseline (docs/quality-baseline.md) was captured with npm-groovy-lint
// 17.0.5 (which embeds CodeNarc); this ruleset reproduces an equivalent gate so
// CI / local runs need no npm. It mirrors .groovylintrc.json where reasonable:
//   - "extends": "all"  -> include the standard CodeNarc rulesets below
//   - LineLength length = 160
//   - Indentation spacesPerIndentLevel = 2
//   - the project-specific disabled rules are excluded per ruleset
//
// The gate is "no net increase per modified file vs. baseline" (R18.4), so the
// Gradle codenarc tasks run with ignoreFailures = true (see build.gradle); the
// report counts are what matter, not a hard pass/fail.
ruleset {

  description 'hubitat-flair-vents DAB v2 — CodeNarc gate (equivalent to .groovylintrc.json "all")'

  ruleset('rulesets/basic.xml')
  ruleset('rulesets/braces.xml')
  ruleset('rulesets/concurrency.xml')

  ruleset('rulesets/convention.xml') {
    // Disabled in .groovylintrc.json (project favors `def` and terse style).
    exclude 'NoDef'
    exclude 'MethodParameterTypeRequired'
    exclude 'MethodReturnTypeRequired'
    exclude 'VariableTypeRequired'
    exclude 'ImplicitClosureParameter'
    exclude 'PublicMethodsBeforeNonPublicMethods'
    exclude 'TrailingComma'
    exclude 'CompileStatic'
  }

  ruleset('rulesets/design.xml')

  ruleset('rulesets/dry.xml') {
    exclude 'DuplicateListLiteral'
    exclude 'DuplicateMapLiteral'
    exclude 'DuplicateNumberLiteral'
    exclude 'DuplicateStringLiteral'
  }

  ruleset('rulesets/exceptions.xml')

  ruleset('rulesets/formatting.xml') {
    exclude 'SpaceAroundMapEntryColon'
    LineLength {
      length = 160
    }
    Indentation {
      spacesPerIndentLevel = 2
    }
  }

  ruleset('rulesets/generic.xml')
  ruleset('rulesets/groovyism.xml')
  ruleset('rulesets/imports.xml')

  ruleset('rulesets/junit.xml') {
    exclude 'JUnitPublicNonTestMethod'
  }

  ruleset('rulesets/logging.xml')

  ruleset('rulesets/naming.xml') {
    exclude 'ClassNameSameAsFilename'
    exclude 'MethodName'
  }

  ruleset('rulesets/security.xml') {
    exclude 'JavaIoPackageAccess'
  }

  ruleset('rulesets/serialization.xml')

  ruleset('rulesets/size.xml') {
    exclude 'MethodCount'
    exclude 'ParameterCount'
  }

  ruleset('rulesets/unnecessary.xml') {
    exclude 'UnnecessaryGetter'
    exclude 'UnnecessaryReturnKeyword'
  }

  ruleset('rulesets/unused.xml') {
    exclude 'UnusedMethodParameter'
  }
}
