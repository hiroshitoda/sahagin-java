package org.sahagin.java.srctreegen;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.sahagin.java.adapter.AdapterContainer;
import org.sahagin.java.additionaltestdoc.AdditionalClassTestDoc;
import org.sahagin.java.additionaltestdoc.AdditionalFuncTestDoc;
import org.sahagin.java.additionaltestdoc.AdditionalPage;
import org.sahagin.java.additionaltestdoc.AdditionalTestDocs;
import org.sahagin.share.CommonUtils;
import org.sahagin.share.IllegalTestScriptException;
import org.sahagin.share.Logging;
import org.sahagin.share.srctree.PageClass;
import org.sahagin.share.srctree.SrcTree;
import org.sahagin.share.srctree.TestClass;
import org.sahagin.share.srctree.TestClassTable;
import org.sahagin.share.srctree.TestFuncTable;
import org.sahagin.share.srctree.TestFunction;
import org.sahagin.share.srctree.TestMethod;
import org.sahagin.share.srctree.code.Code;
import org.sahagin.share.srctree.code.CodeLine;
import org.sahagin.share.srctree.code.StringCode;
import org.sahagin.share.srctree.code.SubMethodInvoke;
import org.sahagin.share.srctree.code.UnknownCode;

public class SrcTreeGenerator {
    private static Logger logger = Logging.getLogger(SrcTreeGenerator.class.getName());
    private AdditionalTestDocs additionalTestDocs;

    public SrcTreeGenerator(AdditionalTestDocs additionalTestDocs) {
        this.additionalTestDocs = additionalTestDocs;
    }

    // result first value .. TestDoc value. return null if no TestDoc found
    // result second value.. isPage
    private Pair<String, Boolean> getTestDoc(ITypeBinding type) {
        // Page testDoc is prior to TestDoc value
        String pageTestDoc = ASTUtils.getPageTestDoc(type);
        if (pageTestDoc != null) {
            return Pair.of(pageTestDoc, true);
        }

        String testDoc = ASTUtils.getTestDoc(type);
        if (testDoc != null) {
            return Pair.of(testDoc, false);
        }
        AdditionalClassTestDoc additional
        = additionalTestDocs.getClassTestDoc(type.getQualifiedName());
        if (additional != null) {
            return Pair.of(additional.getTestDoc(), additional instanceof AdditionalPage);
        }
        return Pair.of(null, false);
    }

    // value (return null if no TestDoc found) and stepInCapture value pair
    private Pair<String, Boolean> getTestDoc(IMethodBinding method) {
        Pair<String, Boolean> pair = ASTUtils.getTestDoc(method);
        if (pair != null) {
            return pair;
        }
        AdditionalFuncTestDoc additional
        = additionalTestDocs.getFuncTestDoc(ASTUtils.qualifiedMethodName(method));
        if (additional != null) {
            return Pair.of(additional.getTestDoc(), additional.isStepInCapture());
        }
        return null;
    }

    private boolean isSubFunction(IMethodBinding methodBinding) {
        // rootFuntion also can have its TestDoc value
        if (AdapterContainer.globalInstance().isRootFunction(methodBinding)) {
            return false;
        }
        return getTestDoc(methodBinding) != null;
    }

    // srcFiles..parse target files
    // classPathEntries.. all class paths (class file containing directory or jar file) srcFiles depend
    private static void parseAST(
            String[] srcFiles, String srcEncoding, String[] classPathEntries, FileASTRequestor requestor) {
        ASTParser parser = ASTParser.newParser(AST.JLS4);
        Map<?, ?> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_1_5, options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setEnvironment(classPathEntries, null, null, true);
        String[] srcEncodings = new String[srcFiles.length];
        for (int i = 0; i < srcEncodings.length; i++) {
            srcEncodings[i] = srcEncoding;
        }
        parser.createASTs(
                srcFiles, srcEncodings, new String[]{}, requestor, null);
    }

    // Get all root methods and its class as TestRootClass.
    // Each TestRootClass owns only TestRootMethod, and TestSubMethod information is not set yet.
    // methods does not have codeBody information yet.
    private class CollectRootVisitor extends ASTVisitor {
        private TestClassTable rootClassTable;
        private TestFuncTable rootMethodTable;

        // old value in rootClassTable is not replaced.
        // old value in rootMethodTable is replaced.
        public CollectRootVisitor(
                TestClassTable rootClassTable, TestFuncTable rootMethodTable) {
            this.rootClassTable = rootClassTable;
            this.rootMethodTable = rootMethodTable;
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            IMethodBinding methodBinding = node.resolveBinding();
            if (!AdapterContainer.globalInstance().isRootFunction(methodBinding)) {
                return super.visit(node);
            }

            ITypeBinding classBinding = methodBinding.getDeclaringClass();
            if (!classBinding.isClass()) {
                throw new RuntimeException("not supported yet: " + classBinding);
            }

            TestClass rootClass = rootClassTable.getByKey(classBinding.getKey());
            if (rootClass == null) {
                Pair<String, Boolean> pair = getTestDoc(classBinding);
                if (pair.getRight()) {
                    rootClass = new PageClass(); // though root class cannot be page class
                } else {
                    rootClass = new TestClass();
                }
                rootClass.setKey(classBinding.getKey());
                rootClass.setQualifiedName(classBinding.getQualifiedName());
                rootClass.setTestDoc(pair.getLeft());
                rootClassTable.addTestClass(rootClass);
            }

            TestMethod testMethod = new TestMethod();
            testMethod.setKey(methodBinding.getKey());
            testMethod.setQualifiedName(ASTUtils.qualifiedMethodName(methodBinding));
            Pair<String, Boolean> pair = getTestDoc(methodBinding);
            if (pair != null) {
                // pair is null if the root method does not have TestDoc annotation
                testMethod.setTestDoc(pair.getLeft());
                testMethod.setStepInCapture(pair.getRight());
            }
            for (Object element : node.parameters()) {
                if (!(element instanceof SingleVariableDeclaration)) {
                    throw new RuntimeException("not supported yet: " + element);
                }
                SingleVariableDeclaration varDecl = (SingleVariableDeclaration)element;
                testMethod.addArgVariable(varDecl.getName().getIdentifier());
            }
            testMethod.setTestClassKey(rootClass.getKey());
            testMethod.setTestClass(rootClass);
            rootMethodTable.addTestFunction(testMethod);

            rootClass.addTestMethodKey(testMethod.getKey());
            rootClass.addTestMethod(testMethod);

            return super.visit(node);
        }
    }

    private class CollectRootRequestor extends FileASTRequestor {
        private TestClassTable rootClassTable;
        private TestFuncTable rootMethodTable;

        public CollectRootRequestor() {
            rootClassTable = new TestClassTable();
            rootMethodTable = new TestFuncTable();
        }

        public TestClassTable getRootClassTable() {
            return rootClassTable;
        }

        public TestFuncTable getRootMethodTable() {
            return rootMethodTable;
        }

        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            ast.accept(new CollectRootVisitor(rootClassTable, rootMethodTable));
        }
    }

    private class CollectSubVisitor extends ASTVisitor {
        private TestClassTable subClassTable;
        private TestFuncTable subMethodTable;
        private TestClassTable rootClassTable;

        // rootClassTable if only for read, not write any data.
        // old value in subClassTable is not replaced.
        // old value in subMethodTable is replaced.
        public CollectSubVisitor(TestClassTable rootClassTable,
                TestClassTable subClassTable, TestFuncTable subMethodTable) {
            this.rootClassTable = rootClassTable;
            this.subClassTable = subClassTable;
            this.subMethodTable = subMethodTable;
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            IMethodBinding methodBinding = node.resolveBinding();
            if (!isSubFunction(methodBinding)) {
                return super.visit(node);
            }

            ITypeBinding classBinding = methodBinding.getDeclaringClass();
            if (!classBinding.isClass()) {
                throw new RuntimeException("not supported yet: " + classBinding);
            }

            TestClass testClass = rootClassTable.getByKey(classBinding.getKey());
            if (testClass == null) {
                testClass = subClassTable.getByKey(classBinding.getKey());
                if (testClass == null) {
                    Pair<String, Boolean> pair = getTestDoc(classBinding);
                    if (pair.getRight()) {
                        testClass = new PageClass();
                    } else {
                        testClass = new TestClass();
                    }
                    testClass.setKey(classBinding.getKey());
                    testClass.setQualifiedName(classBinding.getQualifiedName());
                    testClass.setTestDoc(pair.getLeft());
                    subClassTable.addTestClass(testClass);
                }
            }

            TestMethod testMethod = new TestMethod();
            testMethod.setKey(methodBinding.getKey());
            testMethod.setQualifiedName(ASTUtils.qualifiedMethodName(methodBinding));
            Pair<String, Boolean> pair = getTestDoc(methodBinding);
            testMethod.setTestDoc(pair.getLeft());
            testMethod.setStepInCapture(pair.getRight());
            for (Object element : node.parameters()) {
                if (!(element instanceof SingleVariableDeclaration)) {
                    throw new RuntimeException("not supported yet: " + element);
                }
                SingleVariableDeclaration varDecl = (SingleVariableDeclaration)element;
                testMethod.addArgVariable(varDecl.getName().getIdentifier());
            }
            testMethod.setTestClassKey(testClass.getKey());
            testMethod.setTestClass(testClass);
            subMethodTable.addTestFunction(testMethod);

            testClass.addTestMethodKey(testMethod.getKey());
            testClass.addTestMethod(testMethod);

            return super.visit(node);
        }
    }

    private class CollectSubRequestor extends FileASTRequestor {
        private TestClassTable subClassTable;
        private TestFuncTable subMethodTable;
        private TestClassTable rootClassTable;

        public CollectSubRequestor(TestClassTable rootClassTable) {
            this.rootClassTable = rootClassTable;
            subClassTable = new TestClassTable();
            subMethodTable = new TestFuncTable();
        }

        public TestClassTable getSubClassTable() {
            return subClassTable;
        }

        public TestFuncTable getSubMethodTable() {
            return subMethodTable;
        }

        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            ast.accept(new CollectSubVisitor(
                    rootClassTable, subClassTable, subMethodTable));
        }
    }

    private class CollectCodeBodyVisitor extends ASTVisitor {
        private TestFuncTable rootMethodTable;
        private TestFuncTable subMethodTable;
        private CompilationUnit compilationUnit;

        // set code body information to method table
        public CollectCodeBodyVisitor(TestFuncTable rootMethodTable, TestFuncTable subMethodTable,
                CompilationUnit compilationUnit) {
            this.rootMethodTable = rootMethodTable;
            this.subMethodTable = subMethodTable;
            this.compilationUnit = compilationUnit;
        }

        private Code methodBindingCode(IMethodBinding binding, List<?> arguments, String original) {
            if (binding == null) {
                UnknownCode unknownCode = new UnknownCode();
                unknownCode.setOriginal(original);
                return unknownCode;
            }

            String uniqueKey = binding.getKey();
            TestFunction invocationFunc = subMethodTable.getByKey(uniqueKey);
            if (invocationFunc == null) {
                // TODO using additionalTestDocKey is temporal logic..
                // TODO What does binding.getName method return for constructor method??
                String additionalUniqueKey = AdditionalTestDocsSetter.additionalTestDocKey(
                        binding.getDeclaringClass().getQualifiedName() + "." + binding.getName());
                invocationFunc = subMethodTable.getByKey(additionalUniqueKey);
                if (invocationFunc == null) {
                    UnknownCode unknownCode = new UnknownCode();
                    unknownCode.setOriginal(original);
                    return unknownCode;
                }
            }
            SubMethodInvoke subMethodInvoke = new SubMethodInvoke();
            subMethodInvoke.setSubFunctionKey(invocationFunc.getKey());
            subMethodInvoke.setSubFunction(invocationFunc);
            for (Object arg : arguments) {
                Expression exp = (Expression) arg;
                subMethodInvoke.addArg(expressionCode(exp));
            }
            subMethodInvoke.setOriginal(original);
            return subMethodInvoke;
        }

        private Code expressionCode(Expression expression) {
            if (expression == null) {
                StringCode strCode = new StringCode();
                strCode.setValue(null);
                strCode.setOriginal("null");
                return strCode;
            } else if (expression instanceof StringLiteral) {
                StringCode strCode = new StringCode();
                strCode.setValue(((StringLiteral) expression).getLiteralValue());
                strCode.setOriginal(expression.toString().trim());
                return strCode;
            } else if (expression instanceof Assignment) {
                Assignment assignment = (Assignment) expression;
                return expressionCode(assignment.getRightHandSide());
            } else if (expression instanceof MethodInvocation) {
                MethodInvocation invocation = (MethodInvocation) expression;
                IMethodBinding binding = invocation.resolveMethodBinding();
                return methodBindingCode(binding, invocation.arguments(), expression.toString().trim());
            } else if (expression instanceof ClassInstanceCreation) {
                ClassInstanceCreation creation = (ClassInstanceCreation) expression;
                IMethodBinding binding = creation.resolveConstructorBinding();
                return methodBindingCode(binding, creation.arguments(), expression.toString().trim());
            } else{
                UnknownCode unknownCode = new UnknownCode();
                unknownCode.setOriginal(expression.toString().trim());
                return unknownCode;
            }
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            TestFunction testFunc;
            IMethodBinding methodBinding = node.resolveBinding();
            if (AdapterContainer.globalInstance().isRootFunction(methodBinding)) {
                testFunc = rootMethodTable.getByKey(methodBinding.getKey());
            } else if (isSubFunction(methodBinding)) {
                testFunc = subMethodTable.getByKey(methodBinding.getKey());
            } else {
                return super.visit(node);
            }

            List<?> list = node.getBody().statements();
            for (Object obj : list) {
                assert obj instanceof ASTNode;
                ASTNode statementNode = (ASTNode) obj;
                Code code;
                if (statementNode instanceof ExpressionStatement) {
                    Expression expression = ((ExpressionStatement)statementNode).getExpression();
                    code = expressionCode(expression);
                } else if (statementNode instanceof VariableDeclarationStatement) {
                    // TODO assume single VariableDeclarationFragment
                    VariableDeclarationFragment varFrag
                    = (VariableDeclarationFragment)(((VariableDeclarationStatement)statementNode).fragments().get(0));
                    Expression expression = varFrag.getInitializer();
                    code = expressionCode(expression);
                } else {
                    code = new UnknownCode();
                    code.setOriginal(statementNode.toString().trim());
                }

                CodeLine codeLine = new CodeLine();
                codeLine.setStartLine(compilationUnit.getLineNumber(statementNode.getStartPosition()));
                codeLine.setEndLine(compilationUnit.getLineNumber(
                        statementNode.getStartPosition() + statementNode.getLength()));
                codeLine.setCode(code);
                // sometimes original value set by expressionCode method does not equal to the on of statementNode
                code.setOriginal(statementNode.toString().trim());
                testFunc.addCodeBody(codeLine);
            }
            return super.visit(node);
        }
    }

    private class CollectCodeBodyRequestor extends FileASTRequestor {
        private TestFuncTable rootMethodTable;
        private TestFuncTable subMethodTable;

        public CollectCodeBodyRequestor(TestFuncTable rootMethodTable, TestFuncTable subMethodTable) {
            this.rootMethodTable = rootMethodTable;
            this.subMethodTable = subMethodTable;
        }

        @Override
        public void acceptAST(String sourceFilePath, CompilationUnit ast) {
            ast.accept(new CollectCodeBodyVisitor(rootMethodTable, subMethodTable, ast));
        }
    }

    // srcFiles..parse target files
    // srcEncoding.. encoding of srcFiles. "UTF-8" etc
    // classPathEntries.. all class paths (class file containing directory or jar file) srcFiles depend.
    // this path value is similar to --classpath command line argument, but you must give
    // all class containing sub directories even if the class is in a named package
    public SrcTree generate(String[] srcFiles, String srcEncoding, String[] classPathEntries) {
        // collect root class and method table without code body
        CollectRootRequestor rootRequestor = new CollectRootRequestor();
        parseAST(srcFiles, srcEncoding, classPathEntries, rootRequestor);

        // collect sub class and method table without code body
        CollectSubRequestor subRequestor = new CollectSubRequestor(rootRequestor.getRootClassTable());
        parseAST(srcFiles, srcEncoding, classPathEntries, subRequestor);

        // add not used additional TestDoc to the table
        AdditionalTestDocsSetter setter = new AdditionalTestDocsSetter(
                rootRequestor.getRootClassTable(), subRequestor.getSubClassTable(),
                rootRequestor.getRootMethodTable(), subRequestor.getSubMethodTable());
        setter.set(additionalTestDocs);

        // collect code body
        CollectCodeBodyRequestor codeBodyRequestor = new CollectCodeBodyRequestor(
                rootRequestor.getRootMethodTable(), subRequestor.getSubMethodTable());
        parseAST(srcFiles, srcEncoding, classPathEntries, codeBodyRequestor);

        SrcTree result = new SrcTree();
        result.setRootClassTable(rootRequestor.getRootClassTable());
        result.setSubClassTable(subRequestor.getSubClassTable());
        result.setRootFuncTable(rootRequestor.getRootMethodTable());
        result.setSubFuncTable(subRequestor.getSubMethodTable());
        return result;
    }

    private void addToClassPathListFromJarManifest(List<String> classPathList, File jarFile) {
        if (!jarFile.exists()) {
            return; // do nothing
        }
        Manifest manifest = CommonUtils.readManifestFromExternalJar(jarFile);
        // jar class path is sometimes not set at java.class.path property
        // (this case happens for Maven surefire plug-in.
        //  see http://maven.apache.org/surefire/maven-surefire-plugin/examples/class-loading.html)
        String jarClassPathStr = manifest.getMainAttributes().getValue("Class-Path");
        if (jarClassPathStr != null) {
            String[] jarClassPathArray = jarClassPathStr.split(" "); // separator is space character
            addToClassPathList(classPathList, jarClassPathArray);
        }
    }

    private void addToClassPathList(List<String> classPathList, String[] classPathArray) {
        for (String classPath : classPathArray) {
            if (classPath == null || classPath.trim().equals("")) {
                continue;
            }
            String classPathWithoutPrefix;
            if (classPath.startsWith("file:")) {
                // class path in the jar MANIFEST sometimes has this form of class path
                classPathWithoutPrefix = classPath.substring(5);
            } else {
                classPathWithoutPrefix = classPath;
            }
            String absClassPath = new File(classPathWithoutPrefix).getAbsolutePath();

            if (absClassPath.endsWith(".jar")) {
                if (!classPathList.contains(absClassPath)) {
                    classPathList.add(absClassPath);
                    addToClassPathListFromJarManifest(classPathList, new File(absClassPath));
                }
            } else if (absClassPath.endsWith(".zip")) {
                if (!classPathList.contains(absClassPath)) {
                    classPathList.add(absClassPath);
                }
            } else {
                File classPathFile = new File(absClassPath);
                if (classPathFile.isDirectory()) {
                    // needs to add all sub directories
                    // since SrcTreeGenerator does not search classPathEntry sub directories
                    // TODO should add jar file in the sub directories ??
                    Collection<File> subDirCollection = FileUtils.listFilesAndDirs(
                            classPathFile, FileFilterUtils.directoryFileFilter(), FileFilterUtils.trueFileFilter());
                    for (File subDir : subDirCollection) {
                        if (!classPathList.contains(subDir.getAbsolutePath())) {
                            classPathList.add(subDir.getAbsolutePath());
                        }
                    }
                }
            }
        }
    }

    public SrcTree generateWithRuntimeClassPath(File srcRootDir, String srcEncoding)
            throws IllegalTestScriptException {
        // set up srcFilePaths
        if (!srcRootDir.exists()) {
            throw new IllegalArgumentException("directory does not exist: " + srcRootDir.getAbsolutePath());
        }
        Collection<File> srcFileCollection = FileUtils.listFiles(srcRootDir, new String[]{"java"}, true);
        List<File> srcFileList = new ArrayList<File>(srcFileCollection);
        String[] srcFilePaths = new String[srcFileList.size()];
        for (int i = 0; i < srcFileList.size(); i++) {
            srcFilePaths[i] = srcFileList.get(i).getAbsolutePath();
        }

        // set up classPathEntries
        // TODO handle wild card classpath entry
        List<String> classPathList = new ArrayList<String>(64);
        String classPathStr = System.getProperty("java.class.path");
        String[] classPathArray = classPathStr.split(File.pathSeparator);
        addToClassPathList(classPathList, classPathArray);
        for (String classPath : classPathList) {
            logger.info("classPath: " + classPath);
        }
        return generate(srcFilePaths, srcEncoding, classPathList.toArray(new String[0]));
    }

}