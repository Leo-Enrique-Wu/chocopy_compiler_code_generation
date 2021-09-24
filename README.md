# NYU Compiler Construction CSCI-GA.2130/Spring 2021: Programming Assignment 3

This assignment is adapted from https://github.com/cs164berkeley/pa3-chocopy-code-generation with the authors' permission.

See the PA3 document on Piazza for a detailed specification.

## Quickstart

Run the following commands to compile your code generator and run the tests:
```
mvn clean package
java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
  --pass=..s --test --run --dir src/test/data/pa3/sample/
```

The dots in `--pass` make the compiler skip parsing and semantic analysis and go straight to code generation.
`--pass=..s` uses your (`s` for `student`) generator to generate code from an annotated AST (the `.ast.typed` files under `src/test/data/pa3/sample/`).
With the starter code, only one test should pass.
Your main objective is to build a code generator that passes all the provided tests.

`--pass=..r` uses the reference (`r` for `reference`) generator, which should pass all tests.

In addition to running in test mode with `--test`, you can also observe the actual output of your (or reference) generator with:
```
java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
  --pass=..s src/test/data/pa3/sample/op_add.py.ast.typed
```

You can also run all passes on the original `.py` file:
```
java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
  --pass=rrr src/test/data/pa3/sample/op_add.py
```

Once you merge your semantic analysis code from assignment 2, you should be able to use `--pass=sss`.

## Generating vs Running

The above should be familiar from previous assignments.
A new aspect of PA3 is the distinction between generating and running the code.

The following outputs the generated RISC-V assembly (with the usual option to save to a file with `--out`): 
```
java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
  --pass=rrr src/test/data/pa3/sample/op_add.py
```

The following (note the `--run` option) generates the assembly and then _runs it on the bundled RISC-V emulator_:
```
java -cp "chocopy-ref.jar:target/assignment.jar" chocopy.ChocoPy \
  --pass=rrr --run src/test/data/pa3/sample/op_add.py
```
