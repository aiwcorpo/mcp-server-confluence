// Thin wrapper: all logic is in ci/run.sh. Dependencies come from Maven Central, so no stage holds a credential.
// Change requests run unreviewed code, so their stage has no Docker socket; only the main branch gets an agent with the
// Docker socket for the image.
pipeline {
  agent none
  stages {
    stage('verify (change request)') {
      when { changeRequest() }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e' } }
      steps {
        sh 'git config --global --add safe.directory "$WORKSPACE" && ci/run.sh verify'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
    stage('verify (main)') {
      when { branch 'main' }
      agent { docker { image 'maven:3.9.12-eclipse-temurin-25@sha256:4f82a03a7d6679281952d628131299b1be88d7030a49c6a2b7d2ba2642e44e3e' } }
      steps {
        sh 'git config --global --add safe.directory "$WORKSPACE" && ci/run.sh verify'
        stash name: 'build', includes: 'mcp-app/target/mcp-*.jar,mcp-app/target/mcp-manifest.json'
      }
      post {
        always {
          junit allowEmptyResults: false, testResults: '**/target/surefire-reports/TEST-*.xml'
        }
      }
    }
    stage('image (main)') {
      when { branch 'main' }
      agent { docker { image 'docker:27-cli@sha256:851f91d241214e7c6db86513b270d58776379aacc5eb9c4a87e5b47115e3065c'; args '-v /var/run/docker.sock:/var/run/docker.sock -u root' } }
      steps {
        sh 'apk add --no-cache bash git'
        unstash 'build'
        sh 'git config --global --add safe.directory "$WORKSPACE" && ci/run.sh image'
      }
    }
  }
}
