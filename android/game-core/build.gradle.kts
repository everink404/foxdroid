plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":content-model"))
    testImplementation(kotlin("test-junit"))
    testImplementation("com.google.code.gson:gson:2.12.1")
}
sourceSets.test { resources.srcDir("../../shared/test-vectors") }
