pipeline {
  agent any
  
  // 環境變數 【實務上依照自己的機器配置替換】
  environment {       		
        PROJECT_NAME = "GetDockerContainerEnvironmentParameterExample"// 專案名稱
		PROJECT_NAME_FOR_DOCKER = "getdockercontainerenvironmentparameterexample"// DockerName 強制小寫
		GIT_SOURCE_REPOSITORY = "https://github.com/gotoa1234/MyBlogExample.git"// 專案來源
		TARGET_MACHINE_IP = "172.29.155.94"// 對應的部署機器IP
		TARGET_MACHINE_CREDENTIAL = "MyTargetLinux"// 對應部署機器的SSH Server Name
  
        // 設定檔 Git 倉庫資訊
        CONFIG_GIT_URL = "https://github.com/gotoa1234/JenkinsCICDConfigure.git"
        CONFIG_FILE_PATH = "2026_03_14_GitGetPublishSetting/machine.json"
  }  
  
  // 定義單一建置時可異動參數 【實務上依照自己的機器配置替換參數】
  parameters {
        string(name: 'GIT_HASH_TAG', defaultValue: '', description: '指定發布的GIT Hash 標籤(雜湊版號)，預設 head 表示更新最新代碼')
        string(name: 'ASPNETCORE_ENVIRONMENT', defaultValue: 'Development', description: 'ASP NETCORE 環境變數')
        string(name: 'DOTNET_ENVIRONMENT', defaultValue: 'Development', description: 'DOTNET NETCORE 環境變數')
        string(name: 'security_key', defaultValue: 'ProductionKey', description: 'IT 管理金鑰')
  }
  
  stages {
      
    // step 1. start
    stage('Checkout') {
       steps {
            checkout([$class: 'GitSCM', 
                branches: [[name: "remotes/origin/main"]],
                userRemoteConfigs: [[url: "${env.GIT_SOURCE_REPOSITORY}"]]
            ])

            sh """
                git pull origin main
            """

            sh """
                git checkout ${params.GIT_HASH_TAG}
            """
        }
     }
    // step 1. end

    // step 2. start
    stage('2-Publish Main Host') {
	  steps {
	      sshPublisher(publishers: 
	             [sshPublisherDesc(configName: "${env.TARGET_MACHINE_CREDENTIAL}", 
	                                transfers: [
	                                    sshTransfer(cleanRemote: true, 
	                                                   excludes: '', 
	                                                execCommand: '', 
	                                                execTimeout: 120000, 
	                                                    flatten: false, 
	                                              makeEmptyDirs: false, 
	                                          noDefaultExcludes: false, 
	                                           patternSeparator: '[, ]+', 
	                                            remoteDirectory: "var\\dockerbuildimage\\${PROJECT_NAME}\\", 
	                                         remoteDirectorySDF: false, 
	                                               removePrefix: "${PROJECT_NAME}", 
	                                                sourceFiles: "${PROJECT_NAME}\\**")],
	                     usePromotionTimestamp: false, 
	                   useWorkspaceInPromotion: false, 
	                                   verbose: false)
	             ])
	  }
    }
    // step 2. end

   // step 3. start
    stage('3-Build') {
	  steps {
         sshPublisher(
            failOnError: true,
            publishers: [sshPublisherDesc(
            configName: "${env.TARGET_MACHINE_CREDENTIAL}", 
            transfers: [sshTransfer(
                excludes: '', 
                execCommand: "cd /var/dockerbuildimage/${env.PROJECT_NAME} && \
                              dotnet publish ${PROJECT_NAME}.csproj -c Release -o publish --disable-build-servers", 
                execTimeout: 120000, 
                patternSeparator: '[, ]+')], 
            verbose: false)])
	  }
    }   
    // step 3. end
    
	// step 4. start
    stage('4-Publish DockerFile') {
	  steps {
	      sshPublisher(publishers: 
		         [sshPublisherDesc(configName: "${env.TARGET_MACHINE_CREDENTIAL}", 
				                    transfers: [
									    sshTransfer(cleanRemote: false, 
										               excludes: '', 
													execCommand: '', 
													execTimeout: 120000, 
													    flatten: false, 
												  makeEmptyDirs: false, 
											  noDefaultExcludes: false, 
											   patternSeparator: '[, ]+', 
											    remoteDirectory: "var\\dockerbuildimage\\${PROJECT_NAME}", 
											 remoteDirectorySDF: false, 
											       removePrefix: "${PROJECT_NAME}", 
												    sourceFiles: "${PROJECT_NAME}\\Dockerfile")], 
					   usePromotionTimestamp: false, 
					 useWorkspaceInPromotion: false, 
					                 verbose: false)
				])
	  }
    }
    // step 4. end
	
	// step 5. start
    stage('5-Build Image Remotely') {
      steps {
	    sh """
		   echo cd /var/dockerbuildimage/${env.PROJECT_NAME} 
		   echo docker build --no-cache -t ${env.PROJECT_NAME_FOR_DOCKER} .
		   echo docker tag ${env.PROJECT_NAME_FOR_DOCKER}:latest ${env.PROJECT_NAME_FOR_DOCKER}:hash_${params.GIT_HASH_TAG}
		   """
	  
        sshPublisher(
            failOnError: true,
            publishers: [sshPublisherDesc(
            configName: "${env.TARGET_MACHINE_CREDENTIAL}", 
            transfers: [sshTransfer(
                excludes: '', 
                execCommand: "cd /var/dockerbuildimage/${env.PROJECT_NAME} && \
                              docker build --no-cache -t ${env.PROJECT_NAME_FOR_DOCKER} . && \
                              docker tag ${env.PROJECT_NAME_FOR_DOCKER}:latest ${env.PROJECT_NAME_FOR_DOCKER}:hash_${params.GIT_HASH_TAG}", 
                execTimeout: 120000, 
                patternSeparator: '[, ]+')], 
            verbose: true)])
      }
    }
	// step 5. end
	
	// step 6. start
    stage('6-ReConstruct Container') {
      steps {

        sshPublisher(
            failOnError: true,
            publishers: [sshPublisherDesc(
            configName: "${env.TARGET_MACHINE_CREDENTIAL}", 
            transfers: [sshTransfer(
                excludes: '', 
                execCommand: """
                    sudo docker stop ${env.PROJECT_NAME_FOR_DOCKER} || true && \\
                    sudo docker rm ${env.PROJECT_NAME_FOR_DOCKER} || true && \\
                    sudo docker run -d \\
                      --name ${env.PROJECT_NAME_FOR_DOCKER} \\
                      -e ASPNETCORE_ENVIRONMENT=${params.ASPNETCORE_ENVIRONMENT} \\
                      -e DOTNET_ENVIRONMENT=${params.DOTNET_ENVIRONMENT} \\
                      -e security_key=${params.security_key} \\
                      -p 8090:8080 -p 8190:8081 \\
                      --mount type=bind,source=/var/dockervolumes/${env.PROJECT_NAME}/appsettings.json,target=/app/appsettings.json \\
                      --mount type=bind,source=/var/dockervolumes/${env.PROJECT_NAME}/appsettings.Development.json,target=/app/appsettings.Development.json \\
                      ${env.PROJECT_NAME_FOR_DOCKER}:latest
                """,
                execTimeout: 120000, 
                patternSeparator: '[, ]+')], 
            verbose: true)])
      }
    }   
	// step 6. end
	
	// step 7. start
    stage('7-Image Purne') {
      steps {
         sshPublisher(
             failOnError: true,
             publishers: [sshPublisherDesc(
             configName: "${env.TARGET_MACHINE_CREDENTIAL}", 
             transfers: [sshTransfer(
                 excludes: '', 
                 execCommand: "docker image prune -f", 
                 execTimeout: 120000, 
                 patternSeparator: '[, ]+')], 
             verbose: false)])
      }
    }
	// step 7. end
  }
}