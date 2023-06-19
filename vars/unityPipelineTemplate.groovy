String listToGroovyScript(List values) {
    String output = values.join(":selected\", \"")
    return "[\"$output:selected\"]"
}

String constructBuildCommand(String platform, String buildName, String outputFolder, String buildTarget) {
    bat "cd ${outputFolder} || mkdir ${outputFolder}"
    excludeDirectories += "${outputFolder}/*BackUpThisFolder_ButDontShipItWithYourGame/**/*,"
    excludeDirectories += "${outputFolder}/*_BurstDebugInformation_DoNotShip/**/*,"

    buildCommand = env.BAT_COMMAND + "-customBuildName ${buildName} -customBuildPath %CD%\\${outputFolder}\\ -buildTarget ${buildTarget}"
    if (!params.developmentBuild){
        buildCommand += " -releaseCodeOptimization"
    }
    return buildCommand
}

def buildOnPlatforms(List platforms, List xrPlugins) {
    if (platforms.isEmpty()) {
        echo "Platform list is empty!"
        return
    }
    platforms.each { platform ->
        stage("Building: ${platform}") {
            if (platform.contains("XR")) {
                if (xrPlugins.isEmpty()) {
                    echo "xrPlugins list is empty!"
                    return
                }
                xrPlugins.each { plugin ->
                    buildOnXrPlugin(platform, plugin)
                }
                return
            }
            outputFolder = "${env.OUTPUT_FOLDER}\\${platform}"
            buildName = "${env.BUILD_NAME}_${platform}_${currentBuild.number}"
            batCommand = constructBuildCommand(platform, buildName, outputFolder, platform)
            bat "${batCommand}"
        }
    }
}

def buildOnXrPlugin(String platform, String plugin) {
    stage("Building: ${platform} - ${plugin}") {
        outputFolder = "${env.OUTPUT_FOLDER}\\${platform}\\${plugin}"
        buildName = "${env.BUILD_NAME}_${plugin}_${currentBuild.number}"
        batCommand = constructBuildCommand(platform, buildName, outputFolder, "Android") + " -xrPlugin ${plugin}"
        bat "${batCommand}"
    }
}

def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    String buildPlatformsString = listToGroovyScript(pipelineParams.buildPlatforms)
    println("buildPlatformsString: ${buildPlatformsString}")
    String xrPluginsString = ""
    if (buildPlatformsString.contains("XR") && pipelineParams.xrPlugins) {
        String xrPluginsString = listToGroovyScript(pipelineParams.xrPlugins)
    }

    properties([
        parameters([
            [$class: 'ChoiceParameter',
                choiceType: 'PT_CHECKBOX',
                description: 'Choose the target build platform:',
                filterLength: 1,
                filterable: false,
                name: 'BuildPlatforms',
                script: [
                    $class: 'GroovyScript',
                    script: [
                        classpath: [],
                        sandbox: true,
                        script:
                            "return ${buildPlatformsString}"
                    ]
                ]
            ],
            [$class: 'CascadeChoiceParameter',
                choiceType: 'PT_CHECKBOX',
                description: 'Choose the XR Plug-in Provider:',
                filterLength: 1,
                filterable: false,
                name: 'XrPlugins',
                referencedParameters: 'BuildPlatforms',
                script: [
                    $class: 'GroovyScript',
                    fallbackScript: [
                        classpath: [],
                        sandbox: true,
                        script:
                            'return "None"'
                    ],
                    script: [
                        classpath: [],
                        sandbox: true,
                        script:
                            "if (BuildPlatforms.contains(\"XR\")) { return ${xrPluginsString} }"
                    ]
                ]
            ]
        ])
    ])

    pipeline {
        // Variable inputs that modify the behavior of the job
        parameters {
            booleanParam(name: 'developmentBuild', defaultValue: false, description: 'Choose the build type:')
            choice(name: 'scriptingBackend', choices: ['Mono2x', 'IL2CPP'], description: 'Pick scripting backend:')
            choice(name: 'compressionMethod', choices: ['Default', 'Lz4', 'Lz4HC'], description: 'Pick compression method:')
        }

        // Definition of env variables that can be used throughout the pipeline job
        environment {
            // Unity build params
            BUILD_NAME = "${pipelineParams.productName}"
            OUTPUT_FOLDER = "Builds\\CurrentBuild-${currentBuild.number}"
            IS_DEVELOPMENT_BUILD = "${params.developmentBuild}"
            BAT_COMMAND = "${UNITY_EXECUTABLE} -projectPath %CD% -quit -batchmode -nographics -scriptingBackend ${params.scriptingBackend} -productName ${pipelineParams.productName} -executeMethod BuildCommand.PerformBuild "
            BUILD_OPTIONS_ENV_VAR = "CompressWith${params.compressionMethod}"
        }

        options {
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '10', daysToKeepStr: '7', artifactNumToKeepStr: '10', artifactDaysToKeepStr: '7'))
        }

        agent {
            node {
                label "Master-build-agent"
            }
        }

        stages {
            stage('Init build') {
                steps {
                    script {
                        if (!currentBuild.getBuildCauses('jenkins.branch.BranchEventCause').isEmpty() || 
                            !currentBuild.getBuildCauses('com.cloudbees.jenkins.GitHubPushCause').isEmpty() || 
                            currentBuild.number == 1) {
                            platforms = pipelineParams.buildPlatforms
                            if (platforms.contains("XR") && pipelineParams.xrPlugins) {
                                plugins = pipelineParams.xrPlugins
                            }
                            else {
                                plugins = ""
                            }
                        }
                        else {
                            platforms = params.BuildPlatforms.tokenize(',')
                            plugins = params.XrPlugins.tokenize(',')
                        }
                        excludeDirectories = ""
                        buildOnPlatforms(platforms, plugins)
                    }
                }
            }
        }

        //Any action we want to perform after all the steps have succeeded or failed
        post {
            success {
                echo "Success!"
                archiveArtifacts artifacts: "${env.OUTPUT_FOLDER}/**/*", excludes: excludeDirectories, onlyIfSuccessful: true
                bat "@RD /S /Q ${env.OUTPUT_FOLDER}" // remove directory
            }
            failure {
                echo "Failure!"
            }
        }
    }
}
