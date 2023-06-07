String listToGroovyScript(List values) {
    String output = values.join(":selected\", \"")
    return "[\"$output:selected\"]"
}

def buildOnPlatform(String platform, List xrPlugins) {
    stage("Building: ${platform}") {
        if (platform.contains("XR")) {
            if (params.XrPlugins.isEmpty()) {
                plugins = xrPlugins
            }
            else {
                plugins = params.XrPlugins.split(',')
            }
            plugins.each { plugin ->
                buildOnXrPlugin(platform, plugin)
            }
            return
        }
        outputFolder = "${env.OUTPUT_FOLDER}\\${platform}"
        bat "cd ${outputFolder} || mkdir ${outputFolder}"
        excludeDirectories += "${outputFolder}/*BackUpThisFolder_ButDontShipItWithYourGame/**/*,"

        buildName = "${env.BUILD_NAME}_${platform}_${currentBuild.number}"
        batCommand = env.BAT_COMMAND + "-customBuildName ${buildName} -buildTarget ${platform} -customBuildPath %CD%\\${outputFolder}\\ -executeMethod BuildCommand.PerformBuild"
        bat "${batCommand}"
    }
}

def buildOnXrPlugin(String platform, String plugin) {
    stage("Building: ${platform} - ${plugin}") {
        outputFolder = "${env.OUTPUT_FOLDER}\\${platform}\\${plugin}"
        bat "cd ${outputFolder} || mkdir ${outputFolder}"
        excludeDirectories += "${outputFolder}/*BackUpThisFolder_ButDontShipItWithYourGame/**/*,"

        buildName = "${env.BUILD_NAME}_${plugin}_${currentBuild.number}"
        batCommand = env.BAT_COMMAND + "-customBuildName ${buildName} -buildTarget Android -customBuildPath %CD%\\${outputFolder}\\ -xrPlugin ${plugin} -executeMethod BuildCommand.PerformBuild"
        bat "${batCommand}"
    }
}

def excludeDirectoriesFromArtifacts(List platforms) {
}

def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    String buildPlatforms = listToGroovyScript(pipelineParams.buildPlatforms)
    String xrPlugins = listToGroovyScript(pipelineParams.xrPlugins)

    properties([
        parameters([
            [$class: 'ChoiceParameter', 
                choiceType: 'PT_CHECKBOX', 
                description: 'Choose the target build platform.',
                filterLength: 1,
                filterable: false,
                name: 'BuildPlatforms', 
                script: [
                    $class: 'GroovyScript', 
                    script: [
                        classpath: [], 
                        sandbox: true, 
                        script: 
                            "return ${buildPlatforms}"
                    ]
                ]
            ],
            [$class: 'CascadeChoiceParameter', 
                choiceType: 'PT_CHECKBOX', 
                description: 'Choose the XR Plug-in Provider.',
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
                            "if (BuildPlatforms.contains(\"XR\")) { return ${xrPlugins} }"
                    ]
                ]
            ]
        ])
    ])

    pipeline {
        // Variable inputs that modify the behavior of the job
        parameters {
            booleanParam(name: 'developmentBuild', defaultValue: true, description: 'Choose the build type:')
            choice(name: 'scriptingBackend', choices: ['Mono2x', 'IL2CPP'], description: 'Pick scripting backend:')
            choice(name: 'compressionMethod', choices: ['Default', 'Lz4', 'Lz4HC'], description: 'Pick compression method:')
        }

        // Definition of env variables that can be used throughout the pipeline job
        environment {
            // Unity build params
            BUILD_NAME = "${pipelineParams.appName}_${params.scriptingBackend}"
            OUTPUT_FOLDER = "Builds\\CurrentBuild-${currentBuild.number}"
            IS_DEVELOPMENT_BUILD = "${params.developmentBuild}"
            BAT_COMMAND = "${UNITY_EXECUTABLE} -projectPath %CD% -quit -batchmode -nographics -scriptingBackend ${params.scriptingBackend} "
            BUILD_OPTIONS_ENV_VAR = "CompressWith${params.compressionMethod}"
        }

        // Options: add timestamp to job logs
        options {
            timestamps()
        }

        agent {
            node {
                label "Master-build-agent"
            }
        }

        stages {
            stage('Unity build') {
                steps {
                    script {
                        excludeDirectories = ""
                        params.BuildPlatforms.split(',').each { platform ->
                            buildOnPlatform(platform, pipelineParams.xrPlugins)
                        }
                    }
                }
            }
        }

        //Any action we want to perform after all the steps have succeeded or failed 
        post {
            success {
                echo "Success!"
                archiveArtifacts artifacts: "${env.OUTPUT_FOLDER}/**/*", excludes: excludeDirectories, onlyIfSuccessful: true
            }
            failure {
                echo "Failure!"
            }
        }
    }
}
