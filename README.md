# [<img src="ao-logo.png" alt="AO Logo" width="35" height="40">](https://github.com/ao-apps) [AO Jenkins Shared Library](https://github.com/ao-apps/ao-jenkins-shared-library)

[![project: alpha](https://oss.aoapps.com/ao-badges/project-alpha.svg)](https://aoindustries.com/life-cycle#project-alpha)
[![management: preview](https://oss.aoapps.com/ao-badges/management-preview.svg)](https://aoindustries.com/life-cycle#management-preview)
[![semantic versioning: 2.0.0](https://oss.aoapps.com/ao-badges/semver-2.0.0.svg)](https://semver.org/spec/v2.0.0.html)
[![license: LGPL v3](https://oss.aoapps.com/ao-badges/license-lgpl-3.0.svg)](https://www.gnu.org/licenses/lgpl-3.0)

[Jenkins shared library](https://www.jenkins.io/doc/book/pipeline/shared-libraries/) for all AO-supported [Jenkins Pipelines](https://www.jenkins.io/doc/book/pipeline/syntax/).

## Features
* [Pipeline upstream](https://www.jenkins.io/doc/book/pipeline/syntax/) optimizations:
** Automatically determines build priority through analysis of the upstream graph.
** Prunes unnecessary upstreams: when a direct upstream is also a transitive upstream, prune the direct upstream.
** Abort a build when an upstream, either direct or transitive, is not ready (waiting to build, building, of not
   successful).
* Various functions pulled out of `Jenkinsfile` to avoid `General error during class generation: Method too large`.

## Motivation
We currently support 1185 projects with `Jenkinsfile`, each with a per-project header followed by 1140 lines that are
mostly the same.  We've been manually updating all of projects via large-scale copy/paste.  We have scripts that verify
consistency between all these copies, but this still introduces a maintenance overhead for even small changes.

This maintenace overhead is exasperated by having to commit the full Git submodule tree, even for a small build
configuration change.  Furthermore, a full Git submodule tree commit requires managing multiple version branches on
many projects.  This involves deciding whether to commit directly or to merge the change from the previous version's
branch.  So, at least at this time, we do not have a magic script that simply commits all projects.

Finally, we desire to perform the [SonarQube](https://www.sonarsource.com/products/sonarqube/) analysis
stage only when there is a difference in the sources since the previous successful analysis, or if it has been at
least six days since the last analysis.  We will also trigger an off-hours full analysis on Sundays, thus the builds
during the week will only analyse projects that have actually been changed.  This should speed our overall multi-agent
builds that are currently bottlenecked waiting on our single SonarQube instance.

Tracking the data to reliably determine when to perform the analysis requires attaching arbitrary data to the build.
For this, we will be creating a custom `[hudson.model.Action](https://javadoc.jenkins.io/hudson/model/Action.html)`
in this shared library.

## Contact Us
For questions or support, please [contact us](https://aoindustries.com/contact):

Email: [support@aoindustries.com](mailto:support@aoindustries.com)  
Phone: [1-800-519-9541](tel:1-800-519-9541)  
Phone: [+1-251-607-9556](tel:+1-251-607-9556)  
Web: https://aoindustries.com/contact
