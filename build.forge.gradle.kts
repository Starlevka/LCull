plugins {
	alias(libs.plugins.neoforge.moddev.legacyforge)
}

lcullPlatform(Loader.Forge)

val mainSourceSet = sourceSets["main"]

legacyForge {
	version = prop("deps.forge")

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
			sourceSet(mainSourceSet)
		}
	}
}

mixin {
	add(mainSourceSet, "lcull.refmap.json")
	config("lcull.mixins.json")
}

tasks.withType<Jar>().configureEach {
	manifest {
		attributes["MixinConfigs"] = "lcull.mixins.json"
	}
}

repositories {
	mavenCentral()
	maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
}

dependencies {
	annotationProcessor("org.spongepowered:mixin:0.8.5:processor")
	compileOnly("org.jspecify:jspecify:1.0.0")
}