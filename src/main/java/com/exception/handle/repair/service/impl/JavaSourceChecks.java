package com.exception.handle.repair.service.impl;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import com.exception.handle.ai.model.RepairData.SourceSnapshot;

public final class JavaSourceChecks {

	private JavaSourceChecks() {}

	static void validate(SourceSnapshot before, String candidate) throws IOException {
		if (candidate == null || candidate.isBlank() ||
				candidate.stripLeading().startsWith("```")) {
			throw new IllegalArgumentException("Expected a complete Java file without Markdown");
		}
		if (candidate.getBytes(StandardCharsets.UTF_8).length > SourceFileAccess.MAX_SOURCE_BYTES) {
			throw new IllegalArgumentException("Candidate exceeds the 64 KiB limit");
		}
		int dot = before.className().lastIndexOf('.');
		String expectedPackage = before.className().substring(0, dot);
		String expectedName = before.className().substring(dot + 1);
		Parsed original = parse(before.source(), expectedName);
		Parsed proposed = parse(candidate, expectedName);
		if (!expectedPackage.equals(original.packageName())
				|| !expectedPackage.equals(proposed.packageName())) {
			throw new IllegalArgumentException("The source package must stay unchanged");
		}
		ClassTree originalType = namedType(original.types(), expectedName);
		ClassTree proposedType = namedType(proposed.types(), expectedName);
		if (originalType.getKind() != proposedType.getKind()) {
			throw new IllegalArgumentException("The target top-level type kind must stay unchanged");
		}
		if (isPublic(originalType) != isPublic(proposedType)) {
			throw new IllegalArgumentException("The target top-level visibility must stay unchanged");
		}
		for (ClassTree type : proposed.types()) {
			if (isPublic(type) && !type.getSimpleName().contentEquals(expectedName)) {
				throw new IllegalArgumentException("Unexpected public top-level type");
			}
		}

	}

	private static boolean isPublic(ClassTree type) {
		return type.getModifiers().getFlags().contains(Modifier.PUBLIC);
	}

	private static ClassTree namedType(List<ClassTree> types, String name) {
		List<ClassTree> matches = types.stream()
				.filter(type -> type.getSimpleName().contentEquals(name)).toList();
		if (matches.size() != 1) {
			throw new IllegalArgumentException("Expected exactly one top-level type named " + name);
		}
		return matches.getFirst();
	}

	private static Parsed parse(String text, String name) throws IOException {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IOException("A full JDK is required for parse-only Java validation");
		}
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		JavaFileObject source = new SimpleJavaFileObject(
				URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
			@Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return text;
			}
		};
		try (StandardJavaFileManager files = compiler.getStandardFileManager(
				diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
			JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
					List.of("-proc:none", "--source", "21"), null, List.of(source));
			CompilationUnitTree unit = task.parse().iterator().next();
			// Never call analyze(), generate(), or the CompilationTask.call() method.
			List<String> errors = diagnostics.getDiagnostics().stream()
					.filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
					.limit(3).map(d -> "line " + d.getLineNumber() + ": "
							+ d.getMessage(Locale.ROOT)).toList();
			if (!errors.isEmpty()) {
				throw new IllegalArgumentException("Java syntax rejected: " + String.join(";", errors));
			}
			List<ClassTree> types = new ArrayList<>();
			for (Tree declaration : unit.getTypeDecls()) {
				if (declaration instanceof ClassTree type) {
					types.add(type);
				}
			}
			return new Parsed(unit.getPackageName() == null ? ""
					: unit.getPackageName().toString(), List.copyOf(types));
		}
	}

	private record Parsed(String packageName, List<ClassTree> types) {}

}
