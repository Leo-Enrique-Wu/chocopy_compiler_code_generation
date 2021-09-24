import argparse
import subprocess
import os
import sys
import shlex
import re

def genRefSimulateResult(srcFileName:str, dirPath:str):
	
	if not(dirPath is None):
		srcFilePath = dirPath + "/" + srcFileName
	else:
		srcFilePath = srcFileName
	# print ("srcFilePath:%s" % srcFilePath)
	
	outputFilePath = srcFilePath + ".ast.typed.s.result"
	# print ("outputFilePath:%s" % outputFilePath)
	if (os.path.exists(outputFilePath)):
		# print("Result file exists")
		return
	
	testingCmd = "java -cp \"chocopy-ref.jar:target/assignment.jar\" chocopy.ChocoPy " + \
                 srcFilePath + " --run --pass=rrr"
	# print("testingCmd: %s" % testingCmd)
	args = shlex.split(testingCmd)
	process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
	(out, err) = process.communicate()
	
	outMsgs = out.decode("utf-8").splitlines()
	outputFile = open(outputFilePath, "w")
	
	isFirstLine = True
	#print("Output:")
	for outMsg in outMsgs:
		#print(outMsg)
		if isFirstLine:
			isFirstLine = False
			continue
		outputFile.write(outMsg + '\n')
	outputFile.close()
	print("Generated an output file: %s" % outputFilePath)

if __name__ == "__main__":
	
	# Read Params
	parser = argparse.ArgumentParser(description='Lab 1 Autograder')
	parser.add_argument('--src', dest='srcFilePath', action='store', help="The path of the source python file")
	parser.add_argument('--dir', dest='srcDirPath', action='store', help="The path of the source directory")
	parser.add_argument('--m', dest='mode', default='r', action='store', help="The executing mode: s = student; r = ref")
	args = parser.parse_args()
	
	srcFilePath = args.srcFilePath
	srcDirPath = args.srcDirPath
	modePass = args.mode
	
	if not(srcDirPath is None):
		
		# print("srcDirPath: %s" % srcDirPath)
		files = os.listdir(srcDirPath)
		for f in files:
			# print(f)
			pythonFilePattern = re.compile('\w+.py')
			matched = pythonFilePattern.match(f)
			if matched:
				matchedStr = matched.group()
				if matchedStr == f:
					genRefSimulateResult(f, srcDirPath)
	else:
		genRefSimulateResult(srcFilePath, None)
	
	print ("Done!")