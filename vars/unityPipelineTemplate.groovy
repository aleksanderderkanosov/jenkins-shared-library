def call(body) {
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

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
                        sandbox: false, 
                        script: 
                            'return ["StandaloneWindows:selected", "Android:selected", "XR:selected"]'
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
                        sandbox: false, 
                        script: 
                            'return "None"'
                    ],
                    script: [
                        classpath: [],
                        sandbox: false,
                        script:
                            'if (BuildPlatforms.contains("XR")) { return ["Oculus:selected", "Pico:selected"] }'
                    ]
                ]
            ]
        ])
    ])

    pipeline {
        //Variable inputs that modify the behavior of the job
        parameters {
            booleanParam(name: 'developmentBuild', defaultValue: true, description: 'Choose the buildType.')
        }

        //Definition of env variables that can be used throughout the pipeline job
        environment {
            // Unity build params
            BUILD_NAME = "${pipelineParams.appname}_${currentBuild.number}"
            OUTPUT_FOLDER = "Builds\\CurrentBuild-${currentBuild.number}"
            IS_DEVELOPMENT_BUILD = "${params.developmentBuild}"
        }

        // Options: add timestamp to job logs and limiting the number of builds to be kept.
        options {
            timestamps()
        }

        agent {
            node {
                label "Master-build-agent"
            }
        }

        stages {
            stage('Build') {
                steps {
                    script {
                        echo "BuildPlatforms from params: ${params.BuildPlatforms}"
                        echo "BuildPlatforms: ${pipelineParams.buildPlatforms}"
                        echo "XrPlugins from params: ${params.XrPlugins}"
                        echo "XrPlugins: ${pipelineParams.xrPlugins}"
                        echo "appname: ${pipelineParams.appname}"
                        params.BuildPlatforms.split(',').each { platform ->
                            OUTPUT_FOLDER = env.OUTPUT_FOLDER + "\\${platform}"
                            BAT_COMMAND = "${UNITY_EXECUTABLE} -projectPath %CD% -quit -batchmode -nographics -customBuildName ${BUILD_NAME}"
                            if (platform.contains("XR")) {
                                if (params.XrPlugins.isEmpty()) {
                                    plugins = pipelineParams.xrPlugins
                                }
                                else {
                                    plugins = params.XrPlugins.split(',')
                                }
                                plugins.each { plugin ->
                                    echo "plugin: ${plugin}"
                                    OUTPUT_FOLDER = env.OUTPUT_FOLDER + "\\${platform}" + "\\${plugin}"
                                    echo "OUTPUT_FOLDER: ${OUTPUT_FOLDER}"
                                    bat "cd ${OUTPUT_FOLDER} || mkdir ${OUTPUT_FOLDER}"

                                    BAT_COMMAND = BAT_COMMAND + " -buildTarget Android -customBuildPath %CD%\\${OUTPUT_FOLDER}\\ -xrPlugin ${plugin} -executeMethod BuildCommand.PerformBuild"
                                    //bat "${BAT_COMMAND}"
                                }
                            } else {
                                echo "OUTPUT_FOLDER: ${OUTPUT_FOLDER}"
                                bat "cd ${OUTPUT_FOLDER} || mkdir ${OUTPUT_FOLDER}"

                                BAT_COMMAND = BAT_COMMAND + " -buildTarget ${platform} -customBuildPath %CD%\\${OUTPUT_FOLDER}\\ -executeMethod BuildCommand.PerformBuild"
                                //bat "${BAT_COMMAND}"
                            }
                        }
                    }
                }
            }
        }

        //Any action we want to perform after all the steps have succeeded or failed 
        post {
            success {
                echo "Success!"
                //archiveArtifacts artifacts: "${env.OUTPUT_FOLDER}/**/*.*", onlyIfSuccessful: true
            }
            failure {
                echo "Failure!"
            }
        }
    }
}