package chocopy.pa3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import chocopy.common.analysis.*;
import chocopy.common.analysis.types.*;
import chocopy.common.astnodes.*;
import chocopy.common.codegen.*;
import chocopy.common.codegen.RiscVBackend.Register;

import static chocopy.common.codegen.RiscVBackend.Register.*;

/**
 * This is where the main implementation of PA3 will live.
 *
 * <p>
 * A large part of the functionality has already been implemented in the base
 * class, CodeGenBase. Make sure to read through that class, since you will want
 * to use many of its fields and utility methods in this class when emitting
 * code.
 *
 * <p>
 * Also read the PDF spec for details on what the base class does and what APIs
 * it exposes for its sub-class (this one). Of particular importance is knowing
 * what all the SymbolInfo classes contain.
 */
public class CodeGenImpl extends CodeGenBase {

	/**
	 * A code generator emitting instructions to BACKEND.
	 */
	public CodeGenImpl(RiscVBackend backend) {
		super(backend);
	}

	/**
	 * Operation on None.
	 */
	private final Label errorNone = new Label("error.None");
	/**
	 * Division by zero.
	 */
	private final Label errorDiv = new Label("error.Div");
	/**
	 * Index out of bounds.
	 */
	private final Label errorOob = new Label("error.OOB");

	private final Label boxBool = new Label("box.bool");

	private final Label boxInt = new Label("box.int");

	private final Label allChars = new Label("allChars");
	private final Label initChars = new Label("initChars");

	private final Label strcat = new Label("strcat");

	private final Label strPrototype = new Label("$str$prototype");

	private final Label streql = new Label("streql");

	private final Label strneql = new Label("strneql");

	private final Label listConcatLabel = new Label("listConcat");

	private final String SYM_NAME_OFFSET_LEN = "@.__len__";

	private final String SYM_NAME_OFFSET_STR = "@.__str__";

	private boolean emitListConcatFlag = false;

	/**
	 * Emits the top level of the program.
	 *
	 * <p>
	 * This method is invoked exactly once, and is surrounded by some boilerplate
	 * code that: (1) initializes the heap before the top-level begins and (2) exits
	 * after the top-level ends.
	 *
	 * <p>
	 * You only need to generate code for statements.
	 *
	 * @param statements
	 *            top level statements
	 */
	protected void emitTopLevel(List<Stmt> statements) {
		String mainFrameSizeVarName = "@main.size";
		SlotCounter slotCounter = new SlotCounter();
		backend.emitADDI(SP, SP, "-" + mainFrameSizeVarName, "Insert a stack frame for main");
		backend.emitADDI(FP, SP, mainFrameSizeVarName, "Set FP to previous SP.");
		int slotOffset = slotCounter.allocAndClaimSlotFromBottom();
		backend.emitSW(ZERO, FP, slotOffset, "Top saved RA is 0.");
		slotOffset = slotCounter.allocAndClaimSlotFromBottom();
		backend.emitSW(ZERO, FP, slotOffset, "Top saved FP is 0.");
		StmtAnalyzer stmtAnalyzer = new StmtAnalyzer(null, slotCounter);

		// Initialize all characters
		backend.emitJAL(initChars, "");
		for (Stmt stmt : statements) {
			stmt.dispatch(stmtAnalyzer);
		}
		int mainFrameSize = slotCounter.getStackFrameSize();
		backend.defineSym(mainFrameSizeVarName, mainFrameSize);

		backend.emitLI(A0, EXIT_ECALL, "Code for ecall: exit");
		backend.emitEcall(null);
	}

	/**
	 * Emits the code for a function described by FUNCINFO.
	 *
	 * <p>
	 * This method is invoked once per function and method definition. At the code
	 * generation stage, nested functions are emitted as separate functions of their
	 * own. So if function `bar` is nested within function `foo`, you only emit
	 * `foo`'s code for `foo` and only emit `bar`'s code for `bar`.
	 */
	protected void emitUserDefinedFunction(FuncInfo funcInfo) {
		SlotCounter slotCounter = new SlotCounter();
		StmtAnalyzer stmtAnalyzer = new StmtAnalyzer(funcInfo, slotCounter);

		String funcName = funcInfo.getFuncName();
		String funcFrameSizeVarName = "@" + funcName + ".size";
		backend.emitGlobalLabel(funcInfo.getCodeLabel());
		backend.emitADDI(SP, SP, "-" + funcFrameSizeVarName, "Reserve space for stack frame");
		slotCounter.allocAndClaimSlotFromBottom();
		backend.emitSW(RA, SP, funcFrameSizeVarName + "-4", "return address");
		slotCounter.allocAndClaimSlotFromBottom();
		backend.emitSW(FP, SP, funcFrameSizeVarName + "-8", "control link");
		backend.emitADDI(FP, SP, funcFrameSizeVarName, "New FP is at old SP");

		List<StackVarInfo> locals = funcInfo.getLocals();
		for (int i = 0; i < locals.size(); i++) {
			locals.get(i).getInitialValue().dispatch(stmtAnalyzer);
			backend.emitSW(A0, FP, -(i + 2 + 1) * wordSize,
					String.format("Store local variable %s", locals.get(i).getVarName()));
			slotCounter.allocAndClaimSlotFromBottom();
		}

		for (Stmt stmt : funcInfo.getStatements()) {
			stmt.dispatch(stmtAnalyzer);
		}

		backend.emitMV(A0, ZERO, "Returning None implicitly");
		backend.emitLocalLabel(stmtAnalyzer.epilogue, "Epilogue");

		// FIXME: {... reset fp etc. ...}
		int funcFrameSize = slotCounter.getStackFrameSize();
		backend.defineSym(funcFrameSizeVarName, funcFrameSize);
		backend.emitLW(RA, FP, -wordSize, "Get return address");
		backend.emitLW(FP, FP, -2 * wordSize, "Use control link to restore caller's fp");
		backend.emitADDI(SP, SP, funcFrameSizeVarName, "Restore stack pointer");
		backend.emitJR(RA, "Return to caller");
	}

	/**
	 * An analyzer that encapsulates code generation for statements.
	 */
	private class StmtAnalyzer extends AbstractNodeAnalyzer<Void> {
		/*
		 * The symbol table has all the info you need to determine what a given
		 * identifier 'x' in the current scope is. You can use it as follows: SymbolInfo
		 * x = sym.get("x");
		 *
		 * A SymbolInfo can be one the following: - ClassInfo: a descriptor for classes
		 * - FuncInfo: a descriptor for functions/methods - AttrInfo: a descriptor for
		 * attributes - GlobalVarInfo: a descriptor for global variables - StackVarInfo:
		 * a descriptor for variables allocated on the stack, such as locals and
		 * parameters
		 *
		 * Since the input program is assumed to be semantically valid and well-typed at
		 * this stage, you can always assume that the symbol table contains valid
		 * information. For example, in an expression `foo()` you KNOW that
		 * sym.get("foo") will either be a FuncInfo or ClassInfo, but not any of the
		 * other infos and never null.
		 *
		 * The symbol table in funcInfo has already been populated in the base class:
		 * CodeGenBase. You do not need to add anything to the symbol table. Simply
		 * query it with an identifier name to get a descriptor for a function, class,
		 * variable, etc.
		 *
		 * The symbol table also maps nonlocal and global vars, so you only need to
		 * lookup one symbol table and it will fetch the appropriate info for the var
		 * that is currently in scope.
		 */

		/**
		 * Symbol table for my statements.
		 */
		private final SymbolTable<SymbolInfo> sym;

		/**
		 * Label of code that exits from procedure.
		 */
		protected final Label epilogue;

		/**
		 * The descriptor for the current function, or null at the top level.
		 */
		private final FuncInfo funcInfo;

		private SlotCounter slotCounter = null;

		/**
		 * An analyzer for the function described by FUNCINFO0, which is null for the
		 * top level.
		 */
		StmtAnalyzer(FuncInfo funcInfo0, SlotCounter slotCounter) {

			funcInfo = funcInfo0;
			if (funcInfo == null) {
				sym = globalSymbols;
			} else {
				sym = funcInfo.getSymbolTable();
			}
			epilogue = generateLocalLabel();
			this.slotCounter = slotCounter;

		}

		@Override
		public Void analyze(ReturnStmt node) {
			if (node.value != null) {
				// if there is a return value, then generate code for it. It will be stored in
				// A0.
				node.value.dispatch(this);
			}
			// emitUserDefinedFunction has already handled void return
			// both cases need to jump to epilogue of function
			backend.emitJ(epilogue, "Jump to epilogue and prepare to exit.");
			return null;
		}

		@Override
		public Void analyze(CallExpr node) {
			// object instantiation part
			SymbolInfo classSymInfo = this.sym.get(node.function.name);
			if (classSymInfo instanceof ClassInfo) { // create instance
				ClassInfo classInfo = (ClassInfo) classSymInfo;
				// special object
				if (classInfo.getClassName().equals("int") || classInfo.getClassName().equals("bool")) {
					backend.emitMV(A0, ZERO, "Special cases: int and bool unboxed.");
					return null;
				}
				// instantiate template
				backend.emitLA(A0, classInfo.getPrototypeLabel(),
						String.format("Load pointer to prototype of: %s", classInfo.getClassName()));
				backend.emitJAL(objectAllocLabel,
						String.format("Allocate new object of class %s in A0", classInfo.getClassName()));
				int newObjectStoreOffset = slotCounter.allocAndClaimSlotFromBottom();
				backend.emitSW(A0, FP, newObjectStoreOffset,
						String.format("Save alloc address of class %s on stack slot %d", classInfo.getClassName(),
								slotCounter.getFreeSlotFromBottom()));

				// run constructor
				slotCounter.allocateSlot();
				backend.emitSW(A0, SP, 0, "Push same alloc pointer as argument to call __init__");
				// set SP to the last argument instead of the top of stack frame for func call
				// backend.emitADDI(SP, FP, newObjectAsParaOffset, String.format("Set SP to last
				// argument"));
				backend.emitLW(A1, A0, 2 * wordSize, "Load address of object's dispatch table");
				backend.emitLW(A1, A1, 0,
						String.format("Load address of method: %s", classInfo.getMethods().get(0).getFuncName()));
				// __init__
				backend.emitJALR(A1, String.format("Invoke method: %s", classInfo.getMethods().get(0).getFuncName()));
				slotCounter.freeSlotFromFrameTop(1);
				// load the object address back to A0
				backend.emitLW(A0, FP, newObjectStoreOffset,
						String.format("Pop object address of class %s on stack slot %d", classInfo.getClassName(),
								slotCounter.getFreeSlotFromBottom()));
				slotCounter.freeSlot(1);
				return null;
			}
			// funcCall part
			Identifier funcId = node.function;
			FuncType funcType = null;
			if (!(funcId.getInferredType() instanceof FuncType)) {
				return null;
			} else {
				funcType = (FuncType) funcId.getInferredType();
			}

			String funcName = funcId.name;
			SymbolInfo funcSymInfo = this.sym.get(funcName);
			FuncInfo funcInfo = null;
			if (!(funcSymInfo instanceof FuncInfo)) {
				// It should not reach here since Object Instantiation check before
				return null;
			} else {
				funcInfo = (FuncInfo) funcSymInfo;
			}

			List<Expr> argExprs = node.args;
			int argNum = argExprs.size();
			backend.emitInsn("", String.format("Prepare to call func: %s", funcInfo.getFuncName()));

			// Evaluate every actual parameters
			// 1. Assume that after evaluating, the actual parameters will be
			// __ stored at a0(Function return value register)
			// 2. Evaluate actual parameters from left to right
			// 3. Push the actual parameters backward to the stack because,
			// __ according to the implementation guide, "The local variables and
			// __ parameters are stored in reverse order".
			List<ValueType> formalParameters = funcType.parameters;
			int argIdx = 0;
			List<Integer> tempVarOffsetFromFps = new ArrayList<>();

			// Evaluate all the actual parameters and store them as local variables
			for (Expr argExpr : argExprs) {

				ValueType formalParamType = formalParameters.get(argIdx);

				argExpr.dispatch(this); // the result is on A0

				// Parameters, local variables, global variables, and attributes whose static
				// types are int or bool are represented by simple integer values.
				// Only when assigning them to variables of type object is it necessary
				// to "wrap" or "box" them into the object representations so that their
				// actual types can be recovered by functions that expect to receive pointers
				// to objects.
				Type argType = argExpr.getInferredType();
				if (Type.OBJECT_TYPE.equals(formalParamType)
						&& (Type.INT_TYPE.equals(argType) || Type.BOOL_TYPE.equals(argType))) {
					autoboxA0Value(argType);
				}

				// In case the other arguments are expression or functions,
				// storing the evaluation result as a local variables first.
				int offsetFromFp = slotCounter.allocAndClaimSlotFromBottom();
				tempVarOffsetFromFps.add(offsetFromFp);
				backend.emitSW(A0, FP, offsetFromFp, String.format("Store argument %d as a local variable", argIdx));
				argIdx++;

			}

			// Move the value of the actual parameters on the top of the stack frame
			// in backward(arg_n -> arg_{n - 1} -> ... -> arg_0
			Collections.reverse(tempVarOffsetFromFps);
			int offsetFromSp = 0;
			argIdx = argNum - 1;
			for (Integer tempVarOffsetFromFp : tempVarOffsetFromFps) {

				backend.emitLW(T0, FP, tempVarOffsetFromFp,
						String.format("Load argument %d from a local variable on the stack", argIdx));
				slotCounter.freeSlot(1);
				slotCounter.allocateSlot();
				backend.emitSW(T0, SP, offsetFromSp,
						String.format("Store argument %d on the top of the stack frame", argIdx));
				offsetFromSp += wordSize;

			}

			// When calling nested functions, the static link is pushed on the stack before
			// first argument. The static link is not passed to global functions or to
			// methods.
			boolean isNestedFunc = (funcInfo.getDepth() > 0) ? true : false;
			if (isNestedFunc) {
				int offsetFromFrameTop = argNum * wordSize;
				argNum++;
				slotCounter.allocateSlot();
				FuncInfo currentFuncInfo = this.funcInfo;
				backend.emitMV(T0, FP, "Static link to " + currentFuncInfo.getFuncName());
				// Function can be called by itself. Make sure the static link is pointing to
				// the parent function
				while (funcInfo.getParentFuncInfo().getFuncName() != currentFuncInfo.getFuncName()) {
					backend.emitLW(T0, T0, currentFuncInfo.getParams().size() * wordSize,
							"Load static link from " + currentFuncInfo.getFuncName() + " to "
									+ currentFuncInfo.getParentFuncInfo().getFuncName());
					currentFuncInfo = currentFuncInfo.getParentFuncInfo();
				}
				backend.emitSW(T0, SP, offsetFromFrameTop, "Store static link onto the stack");
			}

			// Let the function definition to deal with the declaration of the function

			// set sp to last arg
			// Didn't change sp, so do nothing

			// invoke function
			Label funcLabel = funcInfo.getCodeLabel();
			backend.emitJAL(funcLabel, String.format("Invoke function: %s", funcLabel.labelName));

			// On return, the caller pops the arguments off the stack
			slotCounter.freeSlotFromFrameTop(argNum);

			// Parameters, local variables, global variables, and attributes whose static
			// types are int or bool are represented by simple integer values.
			// Therefore, no need to autobox even when the return type is `int` or `bool`
			return null;

		}

		@Override
		public Void analyze(ExprStmt node) {
			node.expr.dispatch(this);
			return null;

		}

		@Override
		public Void analyze(BinaryExpr node) {

			switch (node.operator) {
			case "<":
			case "<=":
			case ">":
			case ">=":
			case "==":
			case "!=":
			case "is":

				// Evaluate the left expr first and store the value onto the stack,
				// then evaluate the right expr and store the value onto the stack
				node.left.dispatch(this);
				int leftOperandOffsetFromFp = slotCounter.allocAndClaimSlotFromBottom();
				backend.emitSW(A0, FP, leftOperandOffsetFromFp, "Store the value of the left operand onto the stack");

				node.right.dispatch(this);

				// Current stack status:
				// left operand's value
				// ---------------------- <- SP
				// right operand's value
				// ----------------------

				// Load the value of the left operand onto T0
				// The value of the right operand is on A0
				Register leftOperandReg = Register.T0;
				backend.emitLW(leftOperandReg, FP, leftOperandOffsetFromFp,
						"Load the value of the left operand onto T0");
				slotCounter.freeSlot(1); // pop off from the stack

				Register rightOperandReg = A0;

				// Evaluate the value
				Label nextLabel = generateLocalLabel();
				Label trueResultLabel = generateLocalLabel();
				Label falseResultLabel = generateLocalLabel();

				// TODO In ChocoPy Language Manual and Reference, it should accept
				// multi-comparison expression,
				// but for now the reference parser cannot cope with it, so skip for now
				switch (node.operator) {
				// operator in {"<", "<=", ">", ">="}, T_i = T_{i+1} = int
				case "<":
					backend.emitBLT(leftOperandReg, rightOperandReg, trueResultLabel, "Result is true");
					backend.emitJ(falseResultLabel, "Jump to label `falseResult` to update the result to be `false`");
					break;
				case "<=":
					backend.emitBGE(rightOperandReg, leftOperandReg, trueResultLabel,
							"Check whether rightOperand >= leftOperand. If true, then the result is true");
					backend.emitJ(falseResultLabel, "Jump to label `falseResult` to update the result to be `false`");
					break;
				case ">":
					backend.emitBLT(rightOperandReg, leftOperandReg, trueResultLabel,
							"Check whether rightOperand < leftOperand. If true, then the result is true");
					backend.emitJ(falseResultLabel, "Jump to label `falseResult` to update the result to be `false`");
					break;
				case ">=":
					backend.emitBGE(leftOperandReg, rightOperandReg, trueResultLabel,
							"Check whether leftOperand >= rightOperand. If true, then the result is true");
					backend.emitJ(falseResultLabel, "Jump to label `falseResult` to update the result to be `false`");
					break;
				// operator in {"==", "!="}, T_i = T_{i+1} in {int, str, bool}
				case "==":
					if (Type.STR_TYPE.equals(node.left.getInferredType())) {

						// Push streql's arguments onto the stack
						Label functionLabel = streql;
						slotCounter.allocateSlot();
						int offsetFromFrameTop = wordSize;
						backend.emitSW(leftOperandReg, SP, offsetFromFrameTop,
								String.format("Push func(%s) argument %d", functionLabel.labelName, 0));
						slotCounter.allocateSlot();
						backend.emitSW(rightOperandReg, SP, 0,
								String.format("Push func(%s) argument %d", functionLabel.labelName, 1));

						backend.emitJAL(functionLabel, String.format("Invoke func(%s)", functionLabel.labelName));
						slotCounter.freeSlotFromFrameTop(2);

						// Already save the result in A0, so jump to the end directly
						backend.emitJ(nextLabel, "Jump to label `next` to process the next node");

					} else {
						// {int, bool}
						backend.emitBEQ(leftOperandReg, rightOperandReg, trueResultLabel,
								"Check whether leftOperand == rightOperand. If true, then the result is true");
						backend.emitJ(falseResultLabel,
								"Jump to label `falseResult` to update the result to be `false`");
					}
					break;
				case "!=":
					if (Type.STR_TYPE.equals(node.left.getInferredType())) {

						// Push streql's arguments onto the stack
						Label functionLabel = strneql;
						slotCounter.allocateSlot();
						int offsetFromFrameTop = wordSize;
						backend.emitSW(leftOperandReg, SP, offsetFromFrameTop,
								String.format("Push func(%s) argument %d", functionLabel.labelName, 0));
						slotCounter.allocateSlot();
						backend.emitSW(rightOperandReg, SP, 0,
								String.format("Push func(%s) argument %d", functionLabel.labelName, 1));

						backend.emitJAL(functionLabel, String.format("Invoke func(%s)", functionLabel.labelName));
						slotCounter.freeSlotFromFrameTop(2);

						// Already save the result in A0, so jump to the end directly
						backend.emitJ(nextLabel, "Jump to label `next` to process the next node");

					} else {
						backend.emitBNE(leftOperandReg, rightOperandReg, trueResultLabel,
								"Check whether leftOperand != rightOperand. If true, then the result is true");
						backend.emitJ(falseResultLabel,
								"Jump to label `falseResult` to update the result to be `false`");
					}
					break;
				case "is":
					// Check whether the two operand has the same memory address
					backend.emitBEQ(leftOperandReg, rightOperandReg, trueResultLabel,
							"Check whether leftOperand == rightOperand. If true, then the result is true");
					backend.emitJ(falseResultLabel, "Jump to label `falseResult` to update the result to be `false`");
					break;

				default:
				}

				backend.emitLocalLabel(trueResultLabel, "Update the result to be `true`");
				backend.emitADDI(A0, ZERO, 1, "Update A0's value to be 1 to present `true`");
				backend.emitJ(nextLabel, "Jump to label `next` to process the next node");

				backend.emitLocalLabel(falseResultLabel, "Update the result to be `false`");
				backend.emitADDI(A0, ZERO, 0, "Update A0's value to be 0 to present `false`");
				backend.emitJ(nextLabel, "Jump to label `next` to process the next node");

				// Store the return value on A0, then return
				backend.emitLocalLabel(nextLabel, "Process the next node");
				break;
			case "and":
				Label leftFalse = generateLocalLabel();
				node.left.dispatch(this);
				backend.emitBEQZ(A0, leftFalse,
						String.format("Operator %s: short-circuit left operand", node.operator));
				node.right.dispatch(this);
				backend.emitLocalLabel(leftFalse, String.format("Done evaluating operator: %s", node.operator));
				break;
			case "or":
				Label leftTrueLabel = generateLocalLabel();
				node.left.dispatch(this);
				backend.emitBNEZ(A0, leftTrueLabel, "Short circuit branching if left is true");
				node.right.dispatch(this);
				// the result is already stored in A0
				backend.emitLocalLabel(leftTrueLabel, "Done evaluating operator: or");
				break;
			// arithmetic
			case "+":
				if (node.left.getInferredType().isListType() && node.right.getInferredType().isListType()) {
					generateListConcat(node);
				}
			case "-":
			case "*":
			case "//":
			case "%":
				if (node.left.getInferredType().equals(Type.INT_TYPE)
						&& node.right.getInferredType().equals(Type.INT_TYPE)) {
					node.left.dispatch(this);
					// store left result in the top of stack
					int leftResultOffsetFromFp = slotCounter.allocAndClaimSlotFromBottom();
					backend.emitSW(A0, FP, leftResultOffsetFromFp, String.format(
							"Push on stack slot %d (BinaryExpr left result)", slotCounter.getFreeSlotFromBottom()));
					node.right.dispatch(this);
					backend.emitLW(T0, FP, leftResultOffsetFromFp, String.format(
							"Pop stack slot %d ((BinaryExpr left result)", slotCounter.getFreeSlotFromBottom()));
					slotCounter.freeSlot(1);
					// left in T0, right in A0
					switch (node.operator) {
					case "+":
						backend.emitADD(A0, T0, A0, "Arithmetic operations +");
						break;
					case "-":
						backend.emitSUB(A0, T0, A0, "Arithmetic operations -");
						break;
					case "*":
						backend.emitMUL(A0, T0, A0, "Arithmetic operations *");
						break;
					case "//":
					case "%":
						// divisor can not be 0
						Label nonZero = generateLocalLabel();
						backend.emitBNEZ(A0, nonZero, "Ensure non-zero divisor");
						backend.emitJ(errorDiv, "Go to error handler");
						backend.emitLocalLabel(nonZero, "Divisor is non-zero");
						switch (node.operator) {
						case "//":
							backend.emitXOR(T2, T0, A0, "Check for same sign");
							Label diffSign = generateLocalLabel();
							backend.emitBLTZ(T2, diffSign, "Different sign -> need to adjust left operand");
							backend.emitDIV(A0, T0, A0, "Arithmetic operations //");
							Label divEnd = generateLocalLabel();
							backend.emitJ(divEnd, "Goto end of operations //");
							backend.emitLocalLabel(diffSign, "Operands // have differing signs");
							backend.emitSLT(T2, ZERO, A0, "tmp = 1 if right > 0 else 0");
							backend.emitADD(T2, T2, T2, "tmp *= 2");
							backend.emitADDI(T2, T2, -1, "tmp = 1 if right>=0 else -1");
							backend.emitADD(T2, T0, T2, "Adjust left operand");
							backend.emitDIV(T2, T2, A0, "Adjusted division, toward 0");
							backend.emitADDI(A0, T2, -1, "Complete division with diff signs ");
							backend.emitLocalLabel(divEnd, "End of operations//");
							break;
						case "%":
							backend.emitREM(T2, T0, A0, "Arithmetic operations %");
							Label modResult = generateLocalLabel();
							backend.emitBEQZ(T2, modResult, "Remainder = 0, goto return");
							backend.emitXOR(T3, T2, A0, "Check for differing signs");
							backend.emitBGEZ(T3, modResult, "Don't adjust if signs equal.");
							backend.emitADD(T2, T2, A0, "Different sign -> adjust remainder");
							backend.emitLocalLabel(modResult, "Store result");
							backend.emitMV(A0, T2, "Move result to A0");
							break;
						}
						break;
					}
					// str concatenation
				} else if (node.left.getInferredType().equals(Type.STR_TYPE)
						&& node.right.getInferredType().equals(Type.STR_TYPE)) {

					// str concatenation can only happen when the operator is `+`
					// However, if the program use the wrong operator,
					// the semantic error should be detected by the semantic analyzer.
					// Therefore, here should not happen this scenario.
					// So, do nothing.
					if (!("+".equals(node.operator))) {
						break;
					}

					node.left.dispatch(this);

					// Store the result at the bottom of the stack
					int offsetFromFp = slotCounter.allocAndClaimSlotFromBottom();
					backend.emitSW(A0, FP, offsetFromFp,
							"Store the evaluation result of left operand as a local variable");

					node.right.dispatch(this);

					backend.emitLW(T0, FP, offsetFromFp, "Load the evaluation result of left operand from the stack");
					slotCounter.freeSlot(1);
					slotCounter.allocateSlot();
					int offsetFromFrameTop = wordSize;
					backend.emitSW(T0, SP, offsetFromFrameTop,
							String.format("Push func(%s) argument %d", strcat.labelName, 0));

					slotCounter.allocateSlot();
					backend.emitSW(A0, SP, 0, String.format("Push func(%s) argument %d", strcat.labelName, 1));

					// invoke function
					backend.emitJAL(strcat, String.format("Invoke function: %s", strcat.labelName));

					// On return, the caller pops the arguments off the stack
					slotCounter.freeSlotFromFrameTop(2);

				}
				break;
			default:
			}

			return null;

		}

		@Override
		public Void analyze(AssignStmt node) {
			// generate code to store the value
			// I assume value is stored in A0
			node.value.dispatch(this);

			for (Expr expr : node.targets) {
				if (expr instanceof Identifier) {
					generateVarAccess((Identifier) expr, false);
				} else if (expr instanceof IndexExpr) {
					IndexExpr indexExpr = (IndexExpr) expr;
					// there are two values need to be stored on stack

					final int valueToBeAssignedOffset = slotCounter.allocAndClaimSlotFromBottom();
					backend.emitSW(A0, FP, valueToBeAssignedOffset, "store the value to be assigned to the first slot");
					generateListAccessCode(indexExpr);

					// use A0 here because next targets will assume A0 stores the value
					backend.emitLW(A0, FP, valueToBeAssignedOffset, "Load the value to be assigned to T1");
					backend.emitSW(A0, T0, 0, "Assign the value to that address");

					slotCounter.freeSlot(1);
				} else {
					MemberExpr memberExpr = (MemberExpr) expr;
					// need to store the value to be assigned in A0 on stack otherwise it will be
					// overwritten by others
					final int valueToBeAssignedOffset = slotCounter.allocAndClaimSlotFromBottom();
					backend.emitSW(A0, FP, valueToBeAssignedOffset, "Store the value on the stack");

					memberExpr.object.dispatch(this);

					// need to check if it is none before proceed
					// !!!: we can probably optimize here to avoid redundant none check
					// create a label for branching
					Label notNoneLabel = generateLocalLabel();
					backend.emitBNEZ(A0, notNoneLabel, "Check if the object is none otherwise jump");
					// unconditionally jump to error.None (never go back)
					backend.emitJ(errorNone, "Jump to none access error");

					// the object is not none, proceed
					backend.emitLocalLabel(notNoneLabel, "The object is not none");

					// our semantic analyzer does great job to guarantee this is a class.
					ClassInfo classInfo = (ClassInfo) sym.get(memberExpr.object.getInferredType().className());
					// retrieve the position index of this member in the object
					// our semantic analyzer guarantees this attribute must already be defined.
					final int attrIndex = classInfo.getAttributeIndex(memberExpr.member.name);
					backend.emitLW(A1, FP, valueToBeAssignedOffset, "Load the value to be assigned to A1");
					// first 3 fields are type, size and ptr to dispatch table according to object
					// layout
					backend.emitSW(A1, A0, (attrIndex + HEADER_SIZE) * wordSize, "Assign the value to class member");

					// next targets will assume A0 stores the value so a copy here is required
					backend.emitMV(A0, A1, "Store the value back to A0.");
					slotCounter.freeSlot(1);
				}
			}
			return null;
		}

		// Only for testing
		@Override
		public Void analyze(StringLiteral node) {

			String strValue = node.value;
			Label strLabel = constants.getStrConstant(strValue);
			backend.emitLA(A0, strLabel, "Load string literal");
			return null;

		}

		@Override
		public Void analyze(BooleanLiteral node) {
			backend.emitLI(A0, node.value ? 1 : 0, "Load " + (node.value ? "True" : "False") + " to A0");
			return null;

		}

		@Override
		public Void analyze(IntegerLiteral node) {
			backend.emitLI(A0, node.value, String.format("Load integer literal %d", node.value));
			return null;

		}

		@Override
		public Void analyze(NoneLiteral node) {
			backend.emitMV(A0, ZERO, "Load None");
			return null;
		}

		// for declared variable
		@Override
		public Void analyze(Identifier node) {
			generateVarAccess(node, true);
			// the value is ready at A0
			return null;
		}

		@Override
		public Void analyze(UnaryExpr node) {
			Expr operandExpr = node.operand;
			assert (node.operator.equals("-")
					|| node.operator.equals("not")) : "The operator of UnaryExpr is neither '-' or 'not'";
			switch (node.operator) {
			case "-":
				operandExpr.dispatch(this);
				backend.emitSUB(A0, ZERO, A0, "Unary negation");
				break;
			case "not":
				operandExpr.dispatch(this);
				backend.emitSEQZ(A0, A0, "Logical not");
				break;
			default:
				// error
			}
			return null;
		}

		@Override
		public Void analyze(IfExpr node) {

			Label next = generateLocalLabel();
			Label falseCondition = generateLocalLabel();

			Expr condition = node.condition;
			condition.dispatch(this);
			backend.emitBEQZ(A0, falseCondition,
					"Check whether A0(the result of condition) is false. If it is false, then goto `falseCondition`");

			// b_1(true)
			backend.emitInsn("", "True condition: then");
			Expr b1 = node.thenExpr;
			b1.dispatch(this);
			backend.emitJ(next, "Goto `next`");

			// b_2(false)
			backend.emitLocalLabel(falseCondition, "False condition: else");
			Expr b2 = node.elseExpr;
			b2.dispatch(this);

			// next
			backend.emitLocalLabel(next, "Process the next node");
			return null;
		}

		@Override
		public Void analyze(IfStmt node) {
			// get condition bool result in A0
			node.condition.dispatch(this);
			// create branch and endif label
			boolean exitElseBody = !node.elseBody.isEmpty();
			Label elseBranch = exitElseBody ? generateLocalLabel() : null;
			Label endIf = generateLocalLabel();
			// condition branch emit
			if (exitElseBody) {
				// false go to else body
				backend.emitBEQZ(A0, elseBranch, "If false->Goto false branch");
			} else {
				// no else body, false go to end-if
				backend.emitBEQZ(A0, endIf, "If false ->Jump to end-if; No False branch ");
			}
			// then body
			for (Stmt e : node.thenBody) {
				e.dispatch(this);
			}
			backend.emitJ(endIf, "Then body complete; Jump to end-if");
			// else body if exist
			if (exitElseBody) {
				backend.emitLocalLabel(elseBranch, "Else body");
				for (Stmt e : node.elseBody) {
					e.dispatch(this);
				}
			}
			// end
			backend.emitLocalLabel(endIf, "End of if-else statement");
			return null;
		}

		@Override
		public Void analyze(MemberExpr node) {
			// the object's address will store in A0
			node.object.dispatch(this);
			// check object is not None
			Label nonNone = generateLocalLabel();
			backend.emitBNEZ(A0, nonNone, "Ensure object not None");
			backend.emitJ(errorNone, "Goto error handler");
			backend.emitLocalLabel(nonNone, "Object not None");
			// analyze member
			Identifier member = node.member;
			String objName = node.object.getInferredType().className();
			SymbolInfo classInfo = this.sym.get(objName);
			assert classInfo instanceof ClassInfo : "Member object is not a valid class";
			ClassInfo objectClassInfo = (ClassInfo) classInfo;

			if (node.getInferredType().isFuncType()) {
				// load method, A0 is object, A1 is the address of method
				backend.emitLW(A1, A0, 2 * wordSize, String.format("Load address of %s's dispatch table", objName));
				int methodIndex = objectClassInfo.getMethodIndex(member.name);
				backend.emitLW(A1, A1, methodIndex * wordSize,
						String.format("Load address of method: %s.%s(...) in A1", objName, member.name));

			} else {
				// load var
				int attributeIndex = objectClassInfo.getAttributeIndex(member.name);
				backend.emitLW(A0, A0, (attributeIndex + 3) * wordSize,
						String.format("Get attribute: %s.%s", objName, member.name));
			}

			return null;
		}

		@Override
		public Void analyze(MethodCallExpr node) {
			String methodObjName = node.method.object.getInferredType().className();
			String methodName = node.method.member.name;
			// method addr return in A1, Object self return in A0
			node.method.dispatch(this);
			// store method address and object in local
			int methodAddrOffset = slotCounter.allocAndClaimSlotFromBottom();
			backend.emitSW(A1, FP, methodAddrOffset, String.format("Push the method address: %s.%s(...) in slot %d",
					methodObjName, methodName, slotCounter.getFreeSlotFromBottom()));
			int objectOffset = slotCounter.allocAndClaimSlotFromBottom();
			backend.emitSW(A0, FP, objectOffset, String.format("Push the object %s self in slot %d", methodObjName,
					slotCounter.getFreeSlotFromBottom()));

			// Evaluate every actual parameters
			// 1. Assume that after evaluating, the actual parameters will be
			// __ stored at a0(Function return value register)
			// 2. Evaluate actual parameters from left to right
			// 3. Push the actual parameters backward to the stack because,
			// __ according to the implementation guide, "The local variables and
			// __ parameters are stored in reverse order".
			FuncType funcType = (FuncType) node.method.getInferredType();
			assert funcType != null : "Method's InferredType is null";
			List<Expr> actrualArgs = node.args; // exclude self
			List<ValueType> formalParameters = funcType.parameters; // include self
			List<Integer> tempVarOffsetFromFps = new ArrayList<>();
			assert formalParameters.size() == actrualArgs.size()
					+ 1 : "size of formal para and actual para do not match";
			int actualNum = actrualArgs.size();
			backend.emitInsn("", String.format("Prepare to call method: %s.%s(...)", methodObjName, methodName));

			int argIdx = 0;
			// Evaluate all the actual parameters and store them as local variables
			for (Expr argExpr : actrualArgs) {

				ValueType formalParamType = formalParameters.get(argIdx + 1);

				argExpr.dispatch(this); // the result is on A0

				// Parameters, local variables, global variables, and attributes whose static
				// types are int or bool are represented by simple integer values.
				// Only when assigning them to variables of type object is it necessary
				// to "wrap" or "box" them into the object representations so that their
				// actual types can be recovered by functions that expect to receive pointers
				// to objects.
				Type argType = argExpr.getInferredType();
				if (Type.OBJECT_TYPE.equals(formalParamType)
						&& (Type.INT_TYPE.equals(argType) || Type.BOOL_TYPE.equals(argType))) {
					autoboxA0Value(argType);
				}
				// In case the other arguments are expression or functions,
				// storing the evaluation result as a local variables first.
				int offsetFromFp = slotCounter.allocAndClaimSlotFromBottom();
				tempVarOffsetFromFps.add(offsetFromFp);
				backend.emitSW(A0, FP, offsetFromFp,
						String.format("Store actual argument %d as a local variable in slot %d", argIdx,
								slotCounter.getFreeSlotFromBottom()));
				argIdx++;

			}

			// Move the value of the actual parameters on the top of the stack frame
			// in backward(arg_n -> arg_{n - 1} -> ... -> arg_0
			Collections.reverse(tempVarOffsetFromFps);
			int offsetFromSp = 0;
			argIdx = actualNum - 1;
			for (Integer tempVarOffsetFromFp : tempVarOffsetFromFps) {

				backend.emitLW(T0, FP, tempVarOffsetFromFp,
						String.format("Load argument %d from a local variable on the stack", argIdx));
				slotCounter.freeSlot(1);
				slotCounter.allocateSlot();
				backend.emitSW(T0, SP, offsetFromSp,
						String.format("Store argument %d on the top of the stack frame", argIdx));
				offsetFromSp += wordSize;

			}
			// push object self as arg
			backend.emitLW(T0, FP, objectOffset,
					String.format("Pop the object %s in slot %d", methodObjName, slotCounter.getFreeSlotFromBottom()));
			slotCounter.allocateSlot();
			backend.emitSW(T0, SP, actualNum * wordSize,
					String.format("Store object self on the top of the stack frame"));
			// invoke function
			backend.emitLW(A1, FP, methodAddrOffset, String.format("Pop the method address %s.%s(...) in slot %d",
					methodObjName, methodName, slotCounter.getFreeSlotFromBottom()));
			backend.emitJALR(A1, String.format("Invoke method: %s.%s(...)", methodObjName, methodName));

			// free actual args, object self, and method address in the stack
			slotCounter.freeSlotFromFrameTop(actualNum + 1);
			slotCounter.freeSlot(2);
			return null;
		}

		@Override
		public Void analyze(IndexExpr node) {
			Type listType = node.list.getInferredType();
			if (listType.isListType()) {
				generateListAccessCode(node);
				backend.emitLW(A0, T0, 0, "Load value in list to A0");
			} else {
				// this is a str type
				generateStringAccessCode(node);
			}
			return null;
		}

		@Override
		public Void analyze(ListExpr node) {
			if (node.elements.isEmpty()) {
				// this is an empty list so simply return a prototype
				// all fields are initialized for empty list in prototype
				backend.emitLA(A0, listClass.getPrototypeLabel(), "Load list class prototype to A0");
				return null;

			}
			final int elementNum = node.elements.size();

			// a list to store offsets on the stack
			List<Integer> offsetList = new ArrayList<>(elementNum);

			for (Expr expr : node.elements) {
				// generate code for all values in list
				expr.dispatch(this);
				final int offset = slotCounter.allocAndClaimSlotFromBottom();
				backend.emitSW(A0, FP, offset, "Store the result on stack (" + offset + ")");
				offsetList.add(offset);
			}

			// header (3) + attribute len (1) + all element addresses
			final int objectSize = HEADER_SIZE + 1 + elementNum;
			// a0 for prototype and a1 for size
			backend.emitLA(A0, listClass.getPrototypeLabel(), "Load list class prototype to A0");
			backend.emitLI(A1, objectSize, "Load object size " + objectSize + " (in words) to A1");
			backend.emitJAL(objectAllocResizeLabel, "Call alloc2 to allocate memory for the list");
			backend.emitLI(T0, elementNum, "Load element size " + elementNum + " to T0");
			backend.emitSW(T0, A0, "@.__len__", "Store the length of list");

			// there are two options here: either generate static code to lw/sw one by one
			// or generate a loop at runtime to assign values (ref compiler)

			// the first slot in list is 16: [tag, size, ptr to DT, len, elem1, elem2, ...]
			for (int i = 0, offsetInList = HEADER_SIZE + 1; i < elementNum; i++, offsetInList++) {
				backend.emitLW(T0, FP, offsetList.get(i), "Load value (" + (i + 1) + ") from stack");
				backend.emitSW(T0, A0, offsetInList * wordSize, "Store the value (" + (i + 1) + ") in list");
			}
			slotCounter.freeSlot(elementNum);
			// list is ready at A0
			return null;
		}

		@Override
		public Void analyze(WhileStmt node) {
			Label loopTestLabel = generateLocalLabel();
			Label loopLabel = generateLocalLabel();
			// Code for while loop body
			backend.emitJ(loopTestLabel, "Jump to loop test");
			backend.emitLocalLabel(loopLabel, "Top of while loop");
			for (Stmt stmt : node.body) {
				stmt.dispatch(this);
			}
			// Code for condition
			backend.emitLocalLabel(loopTestLabel, "Test loop condition");
			// condition result in A0 (either True or False)
			node.condition.dispatch(this);
			backend.emitBNEZ(A0, loopLabel, "Go to top of while loop if A0 not 0");
			return null;
		}

		@Override
		public Void analyze(ForStmt node) {
			Label forIndexTestLabel = generateLocalLabel();
			Label forBodyLabel = generateLocalLabel();
			Label forEndLabel = generateLocalLabel();

			// use T0 to store the index
			backend.emitLI(T0, 0, "Initialized for loop index to 0");
			int indexOffset = slotCounter.allocAndClaimSlotFromBottom();
			int indexSlot = slotCounter.getFreeSlotFromBottom();
			backend.emitSW(T0, FP, indexOffset, String.format("Store for loop index to slot (%d)", indexSlot));
			int iterableOffset = slotCounter.allocAndClaimSlotFromBottom();
			int iterableSlot = slotCounter.getFreeSlotFromBottom();
			node.iterable.dispatch(this);
			backend.emitSW(A0, FP, iterableOffset, String.format("Store iterable to slot (%d)", iterableSlot));

			backend.emitJ(forIndexTestLabel, "Go to for loop index label");
			backend.emitLocalLabel(forBodyLabel, "Enter for loop body");

			for (Stmt stmt : node.body) {
				stmt.dispatch(this);
			}

			backend.emitLocalLabel(forIndexTestLabel, "Enter for loop index label");

			Type listType = node.iterable.getInferredType();
			// A0 stores the identifier's value
			if (listType.isListType()) {
				generateListAccessCode(null, null, iterableSlot, indexSlot, forEndLabel, "Go to for loop end label");
				backend.emitLW(A0, T0, 0, "Load value in list to A0");
			} else {
				generateStringAccessCode(null, null, iterableSlot, indexSlot, forEndLabel, "Go to for loop end label");
			}

			// Assign value to identifier
			generateVarAccess(node.identifier, false);

			backend.emitLW(T0, FP, indexOffset, String.format("Load for loop index from slot (%d)", indexSlot));
			backend.emitADDI(T0, T0, 1, "Increment for loop index");
			backend.emitSW(T0, FP, indexOffset, String.format("Store for loop index to slot (%d)", indexSlot));
			backend.emitJ(forBodyLabel, "Go to for loop body");
			backend.emitLocalLabel(forEndLabel, "Finish for loop execution");
			return null;
		}
		// FIXME: More, of course.

		// MARK: Helper methods
		private void autoboxA0Value(Type type) {

			if (Type.INT_TYPE.equals(type)) {

				// Only have one actual parameter: the int value
				backend.emitInsn("", "Start to box int");
				slotCounter.allocateSlot();
				backend.emitSW(A0, SP, 0, String.format("Push argument %d", 0));
				backend.emitJAL(boxInt, String.format("Invoke function: %s", boxInt.labelName));
				slotCounter.freeSlotFromFrameTop(1);

			} else if (Type.BOOL_TYPE.equals(type)) {

				// Only have one actual parameter: the bool value
				backend.emitInsn("", "Start to box bool");
				slotCounter.allocateSlot();
				backend.emitSW(A0, SP, 0, String.format("Push argument %d", 0));
				backend.emitJAL(boxBool, String.format("Invoke function: %s", boxBool.labelName));
				slotCounter.freeSlotFromFrameTop(1);

			}

		}

		/**
		 * Generate code for none-check and index check for list type; A0 is loaded with
		 * the list; T0 is loaded with the ptr to that element
		 *
		 * @param expr
		 *            IndexExpr for list
		 *
		 *            Note: If index is out of bound, go to out of bound error label
		 *            "errorOob" with comment "Go to out-of-bounds error and abort".
		 */
		private void generateListAccessCode(IndexExpr expr) {
			generateListAccessCode(expr.list, expr.index, -1, -1, errorOob, "Go to out-of-bounds error and abort");
		}

		/**
		 * Generate code for none-check and index check for list type; A0 is loaded with
		 * the list; T0 is loaded with the ptr to that element
		 *
		 * @param list
		 *            Expr for list
		 * @param index
		 *            Expr for list index
		 * @param listSlot
		 *            If list is null, use -(listSlot*wordSize)(FP) for list value. Not
		 *            used when list is not null.
		 * @param indexSlot
		 *            If index is null, use -(indexSlot*wordSize)(FP) for index value.
		 *            Not used when index is not null.
		 * @param OobLabel
		 *            Label for out of bound index
		 * @param OobComment
		 *            Comment for out of bound index
		 */
		private void generateListAccessCode(Expr list, Expr index, int listSlot, int indexSlot, Label OobLabel,
				String OobComment) {
			if (list != null) {
				list.dispatch(this);
			} else {
				// Load the list to A0 using listSlot
				backend.emitLW(A0, FP, -listSlot * wordSize, String.format("Peek stack slot %d", listSlot));
			}
			// !!!: we can probably optimize here to avoid redundant none check
			// need to check if the list is none
			// create a label for branching
			Label notNoneLabel = generateLocalLabel();
			backend.emitBNEZ(A0, notNoneLabel, "Check if the list is none otherwise jump");
			// unconditionally jump to error.None (never go back)
			backend.emitJ(errorNone, "Jump to none access error");

			// the list is not none, proceed
			backend.emitLocalLabel(notNoneLabel, "the list is not none");

			final int listOffset = slotCounter.allocAndClaimSlotFromBottom();
			backend.emitSW(A0, FP, listOffset, "store the list to the second slot");
			// A0 stores the index
			if (index != null) {
				index.dispatch(this);
			} else {
				backend.emitLW(A0, FP, -indexSlot * wordSize, String.format("Peek stack slot %d", indexSlot));
			}

			// check if the index is in range
			Label validIndexLabel = generateLocalLabel();
			backend.emitLW(T0, FP, listOffset, "Load the list to T0");
			backend.emitLW(T1, T0, "@.__len__", "Load the length of list to t1");
			// this is an unsigned comparison so 0 is considered
			backend.emitBLTU(A0, T1, validIndexLabel, "Ensure 0 <= index < length");
			backend.emitJ(OobLabel, OobComment);

			backend.emitLocalLabel(validIndexLabel, "index is valid");
			// header + 1 (the attribute len)
			backend.emitADDI(A0, A0, HEADER_SIZE + 1, "Get the location of element in word size");
			backend.emitLI(T1, wordSize, "Load word size to T1");
			backend.emitMUL(A0, A0, T1, "Get the location of element in bytes");
			backend.emitADD(T0, T0, A0, "Get the ptr to element");
			slotCounter.freeSlot(1);
		}

		/**
		 * Generate code for index check for string type; A0 is loaded with the indexed
		 * character.
		 *
		 * @param expr
		 *            IndexExpr for string
		 *
		 *            Note: If index is out of bound, go to out of bound error label
		 *            "errorOob" with comment "Go to out-of-bounds error and abort"
		 */
		private void generateStringAccessCode(IndexExpr expr) {
			generateStringAccessCode(expr.list, expr.index, -1, -1, errorOob, "Go to out-of-bounds error and abort");
		}

		/**
		 * Generate code for index check for string type; A0 is loaded with the indexed
		 * character.
		 *
		 * @param list
		 *            Expr for string
		 * @param index
		 *            Expr for string index
		 * @param stringSlot
		 *            If list is null, use -(stringSlot*wordSize)(FP) for string value.
		 *            Not used when list is not null.
		 * @param indexSlot
		 *            If index is null, use -(indexSlot*wordSize)(FP) for index value.
		 *            Not used when index is not null.
		 * @param OobLabel
		 *            Label for out of bound index
		 * @param OobComment
		 *            Comment for out of bound index
		 */
		private void generateStringAccessCode(Expr list, Expr index, int stringSlot, int indexSlot, Label OobLabel,
				String OobComment) {
			Label validIndex = generateLocalLabel();
			if (list != null) {
				slotCounter.allocAndClaimSlotFromBottom();
				stringSlot = slotCounter.getFreeSlotFromBottom();
				list.dispatch(this);
				backend.emitSW(A0, FP, -stringSlot * wordSize, String.format("Push stack slot %d", stringSlot));
			}
			// A0 stores the index
			if (index != null) {
				index.dispatch(this);
			} else {
				backend.emitLW(A0, FP, -indexSlot * wordSize, String.format("Peek stack slot %d", indexSlot));
			}
			// Get length of the string
			backend.emitLW(A1, FP, -stringSlot * wordSize, String.format("Peek stack slot %d", stringSlot));
			backend.emitLW(T0, A1, "@.__len__", String.format("Load attribute: %s", "__len__"));

			// Check whether index (stored in A0) is in a valid range (0<=idx<len)
			backend.emitBLTU(A0, T0, validIndex, "Ensure 0 <= idx < len");
			backend.emitJ(OobLabel, OobComment);

			backend.emitLocalLabel(validIndex, "Index within bounds");
			// Perform index into string
			backend.emitLW(A1, FP, -stringSlot * wordSize, String.format("Peek stack slot %d", stringSlot));
			// Don't need the var in the slot on stack anymore
			slotCounter.freeSlot(1);

			backend.emitADDI(A0, A0, "@.__str__", "Convert index to offset to char in bytes");

			backend.emitADD(A0, A1, A0, "Get pointer to char");
			backend.emitLBU(A0, A0, 0, "Load character");
			backend.emitLI(T1, 20, "");
			backend.emitMUL(A0, A0, T1, "Multiply by size of string object");
			backend.emitLA(T0, allChars, "Index into single-char table");
			backend.emitADD(A0, T0, A0, "");
		}

		private void generateListConcat(BinaryExpr expr) {
			// !!!: I will inline this method back in "analyze(BinaryExpr)" once we finish
			// bug fixing.
			emitListConcatFlag = true;
			expr.left.dispatch(this);
			slotCounter.allocateSlot();
			final int leftListOffset = slotCounter.allocAndClaimSlotFromBottom();
			backend.emitSW(A0, FP, leftListOffset, "Store the left list on stack");
			expr.right.dispatch(this);
			backend.emitLW(A1, FP, leftListOffset, "Load the left list to A1");

			backend.emitSW(A1, SP, wordSize, "Left list as argument 0");
			slotCounter.allocateSlot();
			// sp points to the last args
			backend.emitSW(A0, SP, 0, "Right list argument 1");
			slotCounter.allocateSlot();

			backend.emitJAL(listConcatLabel, "Call listConcat");

			slotCounter.freeSlotFromFrameTop(2);

			// the concatenated list is ready at A0.
		}

		/**
		 * A helper function to generate variable access code (including nonlocals and
		 * globals). This function only uses T0 to load static links for accessing
		 * parent functions or access globals not touching any other registers.
		 * 
		 * @param node
		 *            identifier node
		 * @param loadOperation
		 *            true if generate code for loading value in identifier to A0; false
		 *            for storing value in A0 to identifier
		 */
		private void generateVarAccess(Identifier node, boolean loadOperation) {
			SymbolInfo info = sym.get(node.name);
			if (info instanceof StackVarInfo) {
				StackVarInfo stackVarInfo = (StackVarInfo) info;
				FuncInfo varInfuncInfo = stackVarInfo.getFuncInfo();
				// funcInfo cannot be null here since this is a global assignment statement
				// cannot assign a local variable in a function.
				// adding one after getVarIndex is necessary because 0(fp) points to the last
				// passed argument of caller (implementation guide)
				if (funcInfo == varInfuncInfo) {
					final int offset = funcInfo.getVarIndex(stackVarInfo.getVarName()) - funcInfo.getParams().size()
							+ 1;
					if (loadOperation) {
						backend.emitLW(A0, FP, -offset * wordSize,
								"Load local: " + stackVarInfo.getVarName() + " to A0");
					} else {
						backend.emitSW(A0, FP, -offset * wordSize, "Store A0 to local: " + stackVarInfo.getVarName());
					}
				} else {
					// nonlocal variable (nested func)
					final int nestedness = funcInfo.getDepth() - varInfuncInfo.getDepth();
					FuncInfo parentFuncInfo = funcInfo.getParentFuncInfo();
					/*
					 * (previous stack frame) static link arg0 arg1 argN <- FP (current stack frame)
					 * sp ra locals ------- <- SP
					 */
					backend.emitLW(T0, FP, funcInfo.getParams().size() * wordSize,
							"Load static link from " + funcInfo.getFuncName() + " to " + parentFuncInfo.getFuncName());

					// find the parent function referenced by this nonlocal variable
					for (int i = 0; i < nestedness - 1; i++) {
						backend.emitLW(T0, T0, parentFuncInfo.getParams().size()*wordSize, "Load static link from " + parentFuncInfo.getFuncName() + " to "
								+ parentFuncInfo.getParentFuncInfo().getFuncName());
						parentFuncInfo = parentFuncInfo.getParentFuncInfo();
					}

					final int offset = parentFuncInfo.getVarIndex(stackVarInfo.getVarName())
							- parentFuncInfo.getParams().size() + 1;
					if (loadOperation) {
						backend.emitLW(A0, T0, -offset * wordSize, String.format("Load value from %s.s to A0",
								parentFuncInfo.getFuncName(), stackVarInfo.getVarName()));
					} else {
						backend.emitSW(A0, T0, -offset * wordSize, String.format("Assign value from A0 to %s.%s",
								stackVarInfo.getFuncInfo().getFuncName(), stackVarInfo.getVarName()));
					}
				}
			} else {
				GlobalVarInfo globalVarInfo = (GlobalVarInfo) info;
				if (loadOperation) {
					backend.emitLW(A0, globalVarInfo.getLabel(), "Load global: " + globalVarInfo.getVarName());
				} else {
					backend.emitSW(A0, globalVarInfo.getLabel(), T0, "Assign value from A0 to global variable "
							+ globalVarInfo.getLabel().labelName + " using tmp t0");
				}
			}
		}
	}

	/**
	 * Emits custom code in the CODE segment.
	 *
	 * <p>
	 * This method is called after emitting the top level and the function bodies
	 * for each function.
	 *
	 * <p>
	 * You can use this method to emit anything you want outside of the top level or
	 * functions, e.g. custom routines that you may want to call from within your
	 * code to do common tasks. This is not strictly needed. You might not modify
	 * this at all and still complete the assignment.
	 *
	 * <p>
	 * To start you off, here is an implementation of three routines that will be
	 * commonly needed from within the code you will generate for statements.
	 *
	 * <p>
	 * The routines are error handlers for operations on None, index out of bounds,
	 * and division by zero. They never return to their caller. Just jump to one of
	 * these routines to throw an error and exit the program. For example, to throw
	 * an OOB error: backend.emitJ(errorOob, "Go to out-of-bounds error and abort");
	 */
	protected void emitCustomCode() {

		emitStdFunc("initChars");
		emitStdFunc("allChars");
		emitFuncBoxInt();
		emitFuncBoxBool();
		emitFuncStrCat();
		emitFunStrEql();
		emitFunStrNeql();
		if (emitListConcatFlag) {
			emitListConcatFunc();
		}

		emitErrorFunc(errorNone, ERROR_NONE, "Operation on None");
		emitErrorFunc(errorDiv, ERROR_DIV_ZERO, "Division by zero");
		emitErrorFunc(errorOob, ERROR_OOB, "Index out of bounds");

	}

	private void emitFuncBoxInt() {

		// auto-box `int` routine
		backend.emitGlobalLabel(boxInt);

		// Prologue
		int frameSize = 2 * wordSize;
		int offsetFromFrameTop = 0;
		backend.emitADDI(SP, SP, -1 * frameSize, "Reserve space for stack frame.");
		backend.emitSW(FP, SP, offsetFromFrameTop, "Store control link on the stack");
		offsetFromFrameTop += wordSize;
		backend.emitSW(RA, SP, offsetFromFrameTop, "Store return address to the caller on the stack");
		offsetFromFrameTop += wordSize;
		backend.emitADDI(FP, SP, frameSize, "New fp is at old SP.");

		// Store Prototype address in a0 directly
		backend.emitLA(A0, intClass.getPrototypeLabel(), "Store prototype address to A0");

		backend.emitJAL(objectAllocLabel, "Allocate the object on the heap");

		backend.emitLW(Register.T0, FP, Integer.valueOf(0), "Load arg(the int value) from FP");
		backend.emitSW(Register.T0, A0, getAttrOffset(intClass, "__int__"),
				"Store the int value in the field `__int__`");

		// function epilogues: implemented in the def of function
		// Put return value in A0(The memory address is already on A0)

		// restore stack pointer
		backend.emitADDI(SP, FP, Integer.valueOf(0),
				"Restore stack pointer to point at the last arg of previous active record");

		// get return address
		backend.emitLW(RA, FP, -1 * wordSize, "Restore return address to caller from the stack");

		// restore caller fp
		backend.emitLW(FP, FP, -2 * wordSize, "Restore caller's fp from the stack");

		// return to caller
		backend.emitJR(RA, "Return to caller");

	}

	private void emitFuncBoxBool() {

		// auto-box `int` routine
		backend.emitGlobalLabel(boxBool);

		// Prologue
		int frameSize = 2 * wordSize;
		int offsetFromFrameTop = 0;
		backend.emitADDI(SP, SP, -1 * frameSize, "Reserve space for stack frame.");
		backend.emitSW(FP, SP, offsetFromFrameTop, "Store control link on the stack");
		offsetFromFrameTop += wordSize;
		backend.emitSW(RA, SP, offsetFromFrameTop, "Store return address to the caller on the stack");
		offsetFromFrameTop += wordSize;
		backend.emitADDI(FP, SP, frameSize, "New fp is at old SP.");

		// If the value of A0(the actual parameter) is 0,
		// then return constant.False.
		Label epilogue = new Label("box.bool.epilogue");
		Label returnFalse = new Label("box.bool.returnFalse");
		backend.emitBEQZ(A0, returnFalse,
				String.format("If the parameter is `false`, then goto %s", returnFalse.labelName));
		// Otherwise, return constant.True
		Label constantBoolTrueLabel = this.constants.getBoolConstant(true);
		backend.emitLA(A0, constantBoolTrueLabel,
				String.format("Load address from label(%s)", constantBoolTrueLabel.labelName));
		backend.emitJ(epilogue, String.format("Jump to %s", epilogue.labelName));

		backend.emitLocalLabel(returnFalse, "box.bool.returnFalse");
		Label constantFalseTrueLabel = this.constants.getBoolConstant(false);
		backend.emitLA(A0, constantFalseTrueLabel,
				String.format("Load address from label(%s)", constantFalseTrueLabel.labelName));
		backend.emitJ(epilogue, String.format("Jump to %s", epilogue.labelName));

		// function epilogues: implemented in the def of function
		backend.emitLocalLabel(epilogue, "box.bool.epilogue");
		// Put return value in A0(Already did in above)

		// restore stack pointer
		backend.emitADDI(SP, FP, Integer.valueOf(0),
				"Restore stack pointer to point at the last arg of previous active record");

		// get return address
		backend.emitLW(RA, FP, -1 * wordSize, "Restore return address to caller from the stack");

		// restore caller fp
		backend.emitLW(FP, FP, -2 * wordSize, "Restore caller's fp from the stack");

		// return to caller
		backend.emitJR(RA, "Return to caller");

	}

	/**
	 * Emit string concatenation function
	 */
	private void emitFuncStrCat() {

		backend.emitGlobalLabel(strcat);

		// Prologue
		backend.emitADDI(SP, SP, -16, "Insert a stack frame for strcat");
		backend.emitSW(RA, SP, 12, "Store RA to the caller of strcat");
		backend.emitSW(FP, SP, 8, "Store control link of the caller of strcat");
		backend.emitADDI(FP, SP, 16, "Set FP to previous SP");

		// The function body of strcat
		// -----------------------------
		// | arg_1 : right operand |
		// ----------------------------- <-- FP
		// | arg_0 : left operand |
		// -----------------------------
		Label arg0IsEmptyStr = new Label("arg0IsEmptyStr");
		Label arg1IsEmptyStr = new Label("arg1IsEmptyStr");
		backend.emitLW(Register.T0, FP, 4, "# Load arg_0(left operand)");
		backend.emitLW(Register.T1, Register.T0, SYM_NAME_OFFSET_LEN, "Load len(arg_0)");
		backend.emitLW(Register.T2, FP, 0, "# Load arg_1(right operand)");

		// if (len(arg_0) = 0) {
		// __ // arg_0 is an empty string
		// __ goto arg0IsEmptyStr
		// }
		backend.emitBEQZ(T1, arg0IsEmptyStr, "Check whether len(arg_0) = 0");

		backend.emitLW(Register.T3, Register.T2, SYM_NAME_OFFSET_LEN, "Load len(arg_1)");
		backend.emitBEQZ(T3, arg1IsEmptyStr, "Check whether len(arg_1) = 0");

		// The value of the above will reload after invoking alloc2(Will elaborate the
		// reason later). So, it's okay to reuse T0.
		backend.emitADD(Register.T0, Register.T1, Register.T3, "len(arg_0 + arg_1");

		// Calculate the size of the object(in words):
		// 1. the header: 4(tag, size, dispatchTable, len)
		// 2. the size of str:
		// __ (1) If len + 1(Need to count a NULL byte to present as the end of the
		// ______ string) is a multiple of 4 => (len + 1) // 4
		// __ (2) If not, then (len + 1) // 4 + 1
		// EX: 1. "Okay" + "123" => len + 1 = 7 + 1 = 8(is a multiple of 4)
		// ______ => the size of str = 2(words)
		// ____2. "Okay" + "True" => len + 1 = 9 and 9 % 4 = 1
		// ______ => the size of str = 2 + 1 = 3(words)
		backend.emitADDI(Register.T0, Register.T0, 1, "len + 1");
		// Calculate (len + 1) - ((len + 1) % 4)
		// EX: i = 7(0b111)
		// ___ 1. >> 2 => 1 (0b1)
		// ___ 2. << 2 => 4 (0b100)
		backend.emitSRLI(A1, Register.T0, 2, "len >> 2");
		backend.emitSLLI(Register.T1, A1, 2, "(len >> 2) << 2 => get (len + 1) - ((len + 1) % 4)");
		backend.emitSUB(Register.T0, Register.T0, Register.T1,
				"If len - ((len >> 2) << 2) = 0, then (len + 1) % 4 = 0");
		Label fourMutiple = new Label("strcat.calcObjSize.fourMutiple");
		backend.emitBEQ(Register.T0, ZERO, fourMutiple, "(len + 1) % 4 = 0");

		// notFourMutiple
		backend.emitADDI(A1, A1, 5, "the object size = ((len + 1) // 4) + 1 + 4");
		Label calcObjectSizeNext = new Label("strcat.calcObjSize.next");
		backend.emitJ(calcObjectSizeNext, "Jump to construct a `str` object");

		// FourMutiple
		backend.emitLocalLabel(fourMutiple, "Calculate the size for the case: (len + 1) % 4 = 0");
		backend.emitADDI(A1, A1, 4, "the object size = ((len + 1) // 4) + 4");

		backend.emitLocalLabel(calcObjectSizeNext, "Begin to contruct a `str` object");

		// Construct a new `str` object with specific size(A1)
		backend.emitLA(A0, strPrototype, "Load the address of $str$prototype");
		backend.emitJAL(objectAllocResizeLabel, "Allocate the object on the heap");

		// Load the arguments and the len attributes again
		// Reason:
		// 1. The content of temporary registers do not preserve across function calls.
		// 2. If storing the contents on the current stack frame and loading from it,
		// __ it will need two memory accesses. However, if loading them again from
		// __ the previous stack frame, it only need one memory access.
		backend.emitLW(Register.T0, FP, 4, "# Load arg_0(left operand)");
		backend.emitLW(Register.T1, Register.T0, SYM_NAME_OFFSET_LEN, "Load len(arg_0)");
		backend.emitLW(Register.T2, FP, 0, "# Load arg_1(right operand)");
		backend.emitLW(Register.T3, Register.T2, SYM_NAME_OFFSET_LEN, "Load len(arg_1)");
		backend.emitADD(Register.T4, Register.T1, Register.T3, "len(arg_0 + arg_1");
		backend.emitSW(Register.T4, A0, SYM_NAME_OFFSET_LEN, "Store len in the new `str` object");

		backend.emitMV(A1, Register.T1, "Preserve len(arg_0)");
		backend.emitADDI(T0, T0, SYM_NAME_OFFSET_STR, "Move to the begining of arg_0.str");
		// T1 is already the rest of the length of arg_0
		backend.emitADDI(T4, A0, SYM_NAME_OFFSET_STR, "Move to the begining of new obj's str attribute");
		Label storeStr = new Label("strcat.storeStr");
		backend.emitJAL(storeStr, "Append arg_0.str onto new Obj.str");

		backend.emitADDI(T0, T2, SYM_NAME_OFFSET_STR, "Move to the begining of arg_1.str");
		backend.emitMV(T1, T3, "Setting the rest of len of arg_1");
		backend.emitADDI(T2, A1, SYM_NAME_OFFSET_STR,
				"Calculate the offset of the next address of new str(__str__ + len(arg_0))");
		backend.emitADD(T4, A0, T2, "Move to the next byte of new obj's str attribute after arg_0.str");
		backend.emitJAL(storeStr, "Append arg_1.str onto new Obj.str");

		// T4 now is pointing to the next free byte of the new `str` object.
		// Setting it to be a Null byte to present the end of the string,
		// in case of dirty data
		backend.emitSB(ZERO, T4, 0,
				"Set the next free byte of the new `str` object to be a Null byte(in case dirty data)");

		Label epilogue = new Label("strcat.epilogue");
		backend.emitJ(epilogue, "a0 is the address to the new `str` object");

		// Label: arg0IsEmptyStr
		backend.emitLocalLabel(arg0IsEmptyStr, "Then return a0 = arg1 directly");
		backend.emitMV(A0, T2, "Set a0 = arg1");
		backend.emitJ(epilogue, "a0 is the address to the new `str` object");

		// Label: arg1IsEmptyStr
		backend.emitLocalLabel(arg1IsEmptyStr, "Then return a0 = arg0 directly");
		backend.emitMV(A0, T0, "Set a0 = arg0");
		backend.emitJ(epilogue, "a0 is the address to the new `str` object");

		// Subroutine(storeStr)
		// t0 = pointer to str of object, t1 = rest of len, t4 = idx to new address,
		// t5 = tempStore
		// while (restLen != 0 ) {
		// _ // Copy one byte from t0 to t4
		// _ // Move the pointers to the next byte
		// _ restLen --;
		// }
		Label storeEnd = new Label("strcat.storeEnd");
		backend.emitLocalLabel(storeStr, null);
		backend.emitBEQ(T1, ZERO, storeEnd, null);
		backend.emitLB(T5, T0, 0, null);
		backend.emitSB(T5, T4, 0, null);
		backend.emitADDI(T0, T0, 1, null);
		backend.emitADDI(T4, T4, 1, null);
		backend.emitADDI(T1, T1, -1, null);
		backend.emitJ(storeStr, null);

		backend.emitLocalLabel(storeEnd, null);
		backend.emitJR(RA, null);

		// Epilogue
		backend.emitLocalLabel(epilogue, null);
		backend.emitMV(SP, FP, null);
		backend.emitLW(RA, FP, -4, null);
		backend.emitLW(FP, FP, -8, null);
		backend.emitJR(RA, null);

	}

	private void emitFunStrCompare(Label functionLabel, boolean equalResult) {

		String functionName = functionLabel.labelName;
		backend.emitGlobalLabel(functionLabel);
		Label epilogue = new Label(String.format("%s.epilogue", functionName));
		Label resultIsFalse = new Label(String.format("%s.resultIsFalse", functionName));
		Label resultIsTrue = new Label(String.format("%s.resultIsTrue", functionName));
		Label equal = null;
		Label notEqual = null;
		if (equalResult) {
			equal = resultIsTrue;
			notEqual = resultIsFalse;
		} else {
			equal = resultIsFalse;
			notEqual = resultIsTrue;
		}

		// Prologue
		backend.emitADDI(SP, SP, -16, String.format("Insert a stack frame for %s", functionName));
		backend.emitSW(RA, SP, 12, "Store RA to the caller");
		backend.emitSW(FP, SP, 8, "Store control link of the caller");
		backend.emitADDI(FP, SP, 16, "Set FP to previous SP");

		// Load arguments back
		backend.emitLW(T0, FP, 4, "Load arg_0");
		backend.emitLW(T1, FP, 0, "Load arg_1");
		backend.emitLW(T2, T0, SYM_NAME_OFFSET_LEN, "Load len(arg_0)");
		backend.emitLW(T3, T1, SYM_NAME_OFFSET_LEN, "Load len(arg_1)");

		// Compare the length of arg0 and arg1
		// If not equal, then result is false
		backend.emitBNE(T2, T3, notEqual, "Check whether len(arg_0) != len(arg_1)");

		// If is equal, then compare the contents of arg0 and arg1 byte by byte
		backend.emitADDI(T0, T0, SYM_NAME_OFFSET_STR, "Point to the begining of arg_0.str");
		backend.emitADDI(T1, T1, SYM_NAME_OFFSET_STR, "Point to the begining of arg_1.str");

		Label compareByte = new Label(String.format("%s.compareByte", functionName));
		backend.emitLocalLabel(compareByte, null);
		backend.emitBEQZ(T2, equal, "The rest of len is zero, and all contents are the same");
		backend.emitLB(T3, T0, 0, "Load the current byte og arg_0");
		backend.emitLB(T4, T1, 0, "Load the current byte og arg_0");
		backend.emitBNE(T3, T4, notEqual, "Check whether the bytes are different");
		backend.emitADDI(T0, T0, 1, "Move the pointer of arg_0.str to the next byte");
		backend.emitADDI(T1, T1, 1, "Move the pointer of arg_1.str to the next byte");
		backend.emitADDI(T2, T2, -1, "The result of len -= 1");
		backend.emitJ(compareByte, "Back to next cycle");

		// Result is true
		backend.emitLocalLabel(resultIsTrue, null);
		backend.emitADDI(A0, ZERO, 1, "Set A0 = 1(true) to return");
		backend.emitJ(epilogue, "Goto epilogue and return to the caller");

		// Result is false
		backend.emitLocalLabel(resultIsFalse, null);
		backend.emitADDI(A0, ZERO, 0, "Set A0 = 0(false) to return");

		// Epilogue
		backend.emitLocalLabel(epilogue, null);
		backend.emitMV(SP, FP, null);
		backend.emitLW(RA, FP, -4, null);
		backend.emitLW(FP, FP, -8, null);
		backend.emitJR(RA, null);

	}

	private void emitFunStrEql() {

		emitFunStrCompare(streql, true);

	}

	private void emitFunStrNeql() {

		emitFunStrCompare(strneql, false);

	}

	/**
	 * list concat function takes two arguments: left (args 0) and right (args 1).
	 * The new list is stored to A0.
	 */
	private void emitListConcatFunc() {
		backend.emitGlobalLabel(listConcatLabel);

		backend.emitADDI(SP, SP, -4 * wordSize, "Reserve stack space 4 words");
		backend.emitSW(RA, SP, 3 * wordSize, "Save ra");
		backend.emitSW(FP, SP, 2 * wordSize, "Save previous fp");

		backend.emitADDI(FP, SP, 4 * wordSize, "Update fp");

		Label noneLabel = new Label(listConcatLabel.labelName + "_none");
		backend.emitLW(A0, FP, wordSize, "Load the left list to A0 from arg 0");
		backend.emitBEQZ(A0, noneLabel, "Check if left list is none");

		backend.emitLW(A1, FP, 0, "Load the right list to A1 from arg 1");
		backend.emitBEQZ(A1, noneLabel, "Check if right list is none");

		// get length for two lists
		backend.emitLW(T0, A0, "@.__len__", "Load the length of left list to t0");
		backend.emitLW(T1, A1, "@.__len__", "Load the length of right list to t1");

		backend.emitADD(T2, T0, T1, "Get total length = left + right");

		Label doneLabel = new Label(listConcatLabel.labelName + "_done");
		Label leftEmptyLabel = new Label(listConcatLabel.labelName + "_leftEmpty");

		// left + right = right -> left == 0
		backend.emitBEQ(T2, T1, leftEmptyLabel, "The left list is an empty list");
		// same as above; A0 is already loaded with left list
		backend.emitBEQ(T2, T0, doneLabel, "The right list is an empty list");

		// save both lists on stack since we are preparing to allocate a new list
		// I don't want to save those lengths on stack since they are trivial to get or
		// calculate.
		// -1 and -2 store RA and FP for callee
		backend.emitSW(A0, FP, -3 * wordSize, "Save left list on stack");
		backend.emitSW(A1, FP, -4 * wordSize, "Save right list on stack");

		// allocate list now
		backend.emitLA(A0, listClass.getPrototypeLabel(), "Load list prototype to A0");
		backend.emitADDI(A1, T2, HEADER_SIZE + 1, "Add list header size to get total words");
		backend.emitJAL(objectAllocResizeLabel, "Allocate the list");

		// retrieve those numbers and lists and update len in new list
		backend.emitLW(A1, FP, -3 * wordSize, "Load the left list back to A1");
		backend.emitLW(A2, FP, -4 * wordSize, "Load the right list back to A2");

		backend.emitLW(T1, A1, "@.__len__", "Get length of left list to t1");
		backend.emitLW(T2, A2, "@.__len__", "Get length of right list to t2");
		backend.emitADD(T0, T1, T2, "Get total length to t0 = left (t1) + right (t2)");
		backend.emitSW(T0, A0, "@.__len__", "Store the length to the new list");

		// use t3 for indexing on new list; a3 for indexing on old list
		backend.emitADDI(T3, A0, "@.__elts__", "Move t3 to the first pos in the new list");
		backend.emitADDI(A3, A1, "@.__elts__", "Move a3 to the first pos in the left list");
		backend.emitMV(T0, ZERO, "use t0 as current index to copy; t0 = 0");

		// copying left list
		Label firstCopyingLabel = new Label(listConcatLabel.labelName + "_copyLeft");
		backend.emitLocalLabel(firstCopyingLabel, "Copying the left list");
		backend.emitLW(T4, A3, 0, "Load the element pointed by A3 to T4");
		backend.emitSW(T4, T3, 0, "Store the element to the slot pointed by T3");
		backend.emitADDI(T0, T0, 1, "Increment current index by 1");
		backend.emitADDI(A3, A3, wordSize, "Move A3 (old) to next element");
		backend.emitADDI(T3, T3, wordSize, "Move T3 (new) to next element");
		backend.emitBNE(T0, T1, firstCopyingLabel, "Keep copying until the left list is copied to the new list");

		// copying right list
		backend.emitMV(T0, ZERO, "reset t0 as current index to 0");
		backend.emitADDI(A3, A2, "@.__elts__", "Move a3 to the first pos in the right list");

		Label rightCopyingLabel = new Label(listConcatLabel.labelName + "_copyRight");
		backend.emitLocalLabel(rightCopyingLabel, "Copying the right list");
		backend.emitLW(T4, A3, 0, "Load the element pointed by A3 to T4");
		backend.emitSW(T4, T3, 0, "Store the element to the slot pointed by T3");
		backend.emitADDI(T0, T0, 1, "Increment current index by 1");
		backend.emitADDI(A3, A3, wordSize, "Move A3 (old) to next element");
		backend.emitADDI(T3, T3, wordSize, "Move T3 (new) to next element");
		backend.emitBNE(T0, T2, rightCopyingLabel, "Keep copying until the right list is copied to the new list");

		// done copying
		backend.emitJ(doneLabel, "Skip none error code");

		// if either is none then jump to none error
		backend.emitLocalLabel(noneLabel, "either list is none");
		backend.emitJ(errorNone, "Jump to none access error");

		backend.emitLocalLabel(leftEmptyLabel, "The left list is empty");
		backend.emitMV(A0, A1, "A0 is loaded with right list.");
		// simply fallthrough to exit

		backend.emitLocalLabel(doneLabel, "Finish copying");

		// the new list is ready at a0; ready to return
		backend.emitLW(RA, FP, -1 * wordSize, "Restore ra to previous ra");
		backend.emitLW(FP, FP, -2 * wordSize, "Restore fp to previous fp");
		backend.emitADDI(SP, SP, 4 * wordSize, "Free stack space 4 words");
		backend.emitJR(RA, "Return to caller");

	}

	/** Emit an error routine labeled ERRLABEL that aborts with message MSG. */
	private void emitErrorFunc(Label errLabel, int errCode, String msg) {
		backend.emitGlobalLabel(errLabel);
		backend.emitLI(A0, errCode, "Exit code for: " + msg);
		backend.emitLA(A1, constants.getStrConstant(msg), "Load error message as str");
		backend.emitADDI(A1, A1, getAttrOffset(strClass, "__str__"), "Load address of attribute __str__");
		backend.emitJ(abortLabel, "Abort");
	}

	private class SlotCounter {

		private int freeSlotFromBottom = 0;

		private int currentSlotNumber = 0;

		private int maxSlotNumber = 0;

		/**
		 * Allocate a slot on the stack (It is for allocating slots to push actual
		 * parameters for function invocation, because the actual parameters should be
		 * on the top of the stack frame)
		 * 
		 * @return
		 */
		public void allocateSlot() {
			currentSlotNumber++;
			maxSlotNumber = Math.max(maxSlotNumber, currentSlotNumber);
		}

		/**
		 * Get freeSlotFromBottom
		 * 
		 * @return
		 */
		public int getFreeSlotFromBottom() {
			return freeSlotFromBottom;
		}

		/**
		 * Allocate a slot on the top of the current stack and return the offset from FP
		 * to the slot(for storing temporaries and locals)
		 * 
		 * @return
		 */
		public int allocAndClaimSlotFromBottom() {
			allocateSlot();
			freeSlotFromBottom++;
			return (-1 * freeSlotFromBottom * wordSize);
		}

		/**
		 * Free slots from the frame top(It is to pop the function's actual parameters
		 * off the stack after the return of function invocation)
		 * 
		 * @param popOffSlotNumber
		 */
		public void freeSlotFromFrameTop(int popOffSlotNumber) {
			decreaseSlotNumber(popOffSlotNumber);
		}

		/**
		 * Free slots when pop the temporaries and locals off the stack
		 * 
		 * @param popOffSlotNumber
		 */
		public void freeSlot(int popOffSlotNumber) {
			freeSlotFromBottom -= popOffSlotNumber;
			decreaseSlotNumber(popOffSlotNumber);
		}

		/**
		 * Provide the size of the stack frame
		 * 
		 * @return
		 */
		public int getStackFrameSize() {
			// Let the frame size to be a multiple of 16
			int frameSize = ((maxSlotNumber * wordSize) % 16 == 0) ? maxSlotNumber * wordSize
					: (((maxSlotNumber * wordSize) / 16) + 1) * 16;
			return frameSize;
		}

		private void decreaseSlotNumber(int popOffSlotNumber) {
			currentSlotNumber -= popOffSlotNumber;
		}

	}

}
