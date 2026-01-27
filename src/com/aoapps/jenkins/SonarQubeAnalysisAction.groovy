#!/usr/bin/env groovy
/*
 * ao-jenkins-shared-library - Jenkins shared library for all AO-supported Jenkins Pipelines.
 * Copyright (C) 2026  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-jenkins-shared-library.
 *
 * ao-jenkins-shared-library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-jenkins-shared-library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-jenkins-shared-library.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.jenkins

import hudson.model.Action

class SonarQubeAnalysisAction implements Action, Serializable {

  private static final long serialVersionUID = 1L

  final long analysisTime
  final String gitCommit

  SonarQubeAnalysisAction(long analysisTime, String gitCommit) {
    this.analysisTime = analysisTime
    if (gitCommit == null) throw new IllegalArgumentException("gitCommit required");
    this.gitCommit = gitCommit
  }

  @Override String getIconFileName() { null }
  @Override String getDisplayName() { null }
  @Override String getUrlName() { return null }
}
