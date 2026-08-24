plugins {
	alias(libs.plugins.fabric.loom)
}

lcullPlatform(Loader.FabricM)

loom {
	runs.named("client") {
		client()
		ideConfigGenerated(true)
		runDir = "run/"
		environment = "client"
		programArgs("--username=Dev")
		configName = "Fabric Client"
	}
	runs.named("server") {
		server()
		ideConfigGenerated(true)
		runDir = "run/"
		environment = "server"
		configName = "Fabric Server"
	}
}

repositories {
	mavenCentral()
}

dependencies {
	minecraft("com.mojang:minecraft:${prop("deps.minecraft")}")
	implementation("net.fabricmc:fabric-loader:${prop("deps.fabric-loader")}")
}