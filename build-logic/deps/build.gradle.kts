plugins {
    `kotlin-dsl`
}

// Plugin settings `guzz.deps` (D79) để riêng một jar: plugin settings nạp vào classloader của settings, nơi không có
// AGP; để chung jar với các convention plugin (compileOnly AGP) thì convention plugin không nạp được.
group = "io.github.cozycode890.guzz.buildlogic"

tasks.validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
}

gradlePlugin {
    plugins {
        register("guzzDeps") {
            id = "guzz.deps"
            implementationClass = "GuzzDepsSettingsPlugin"
        }
    }
}
