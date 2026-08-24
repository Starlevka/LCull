plugins {
	alias(libs.plugins.neoforge.moddev)
}

lcullPlatform(Loader.NeoForge)

neoForge {
	version = prop("deps.neoforge")

	runs {
		create("client") {
			client()
		}
		create("server") {
			server()
		}
	}

	mods {
		create("lcull") {
			sourceSet(sourceSets["main"])
		}
	}
}

repositories {
	mavenCentral()
}

dependencies {
	if (sc.current.parsed < "1.21.11") {
		compileOnly("org.jspecify:jspecify:1.0.0")
	}
}