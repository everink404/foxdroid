plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":content-model"))
    implementation(project(":game-core"))
    testImplementation(kotlin("test-junit"))
}
sourceSets.test { resources.srcDir("../../shared/golden-library") }
