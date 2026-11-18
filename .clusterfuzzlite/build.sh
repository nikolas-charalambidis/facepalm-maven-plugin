#!/bin/bash -eu
# Builds the fuzz targets for ClusterFuzzLite (#178): every *FuzzTest class in src/test/java with a static
# fuzzerTestOneInput method. Follows the OSS-Fuzz convention for JVM projects: one launcher script per target in $OUT.

# Compile main and test classes only; quality gates and tests run in the regular CI build.
mvn -B -ntp test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
    -DincludeScope=test -DoutputDirectory=target/fuzz-lib \
    -Dspotless.check.skip -Dpmd.skip -Dcpd.skip -Djacoco.skip -Denforcer.skip -Dfacepalm.skip

mkdir -p "$OUT/classes" "$OUT/test-classes" "$OUT/lib"
cp -r target/classes/. "$OUT/classes/"
cp -r target/test-classes/. "$OUT/test-classes/"
# The Jazzer driver brings its own Jazzer; a second copy on the classpath would conflict with its agent.
find target/fuzz-lib -name '*.jar' ! -name 'jazzer*.jar' -exec cp {} "$OUT/lib/" \;

# Every jar listed explicitly: the driver starts the JVM through JNI, which does not expand "lib/*".
RUNTIME_CLASSPATH="\$this_dir/classes:\$this_dir/test-classes"
for jar in "$OUT"/lib/*.jar; do
    RUNTIME_CLASSPATH="$RUNTIME_CLASSPATH:\$this_dir/lib/$(basename "$jar")"
done

for test_file in $(find src/test/java -name '*FuzzTest.java'); do
    if ! grep -q 'fuzzerTestOneInput' "$test_file"; then
        continue
    fi
    target_class=$(echo "${test_file#src/test/java/}" | sed 's#\.java$##; s#/#.#g')
    fuzzer_name=$(basename "$test_file" .java)

    # The "LLVMFuzzerTestOneInput" comment marks the script as a fuzz target for ClusterFuzzLite.
    cat > "$OUT/$fuzzer_name" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \
--cp=$RUNTIME_CLASSPATH \
--target_class=$target_class \
--jvm_args="\$mem_settings" \
\$@
EOF
    chmod u+x "$OUT/$fuzzer_name"

    # Catastrophic backtracking shows up as a timeout: stop an input after 10 seconds.
    printf '[libfuzzer]\ntimeout = 10\n' > "$OUT/$fuzzer_name.options"
done
