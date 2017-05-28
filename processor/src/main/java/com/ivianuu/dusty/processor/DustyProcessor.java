package com.ivianuu.dusty.processor;

import com.google.auto.service.AutoService;
import com.ivianuu.dusty.annotations.Clear;
import com.squareup.javapoet.JavaFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import static javax.lang.model.element.ElementKind.CLASS;
import static javax.lang.model.element.ElementKind.INTERFACE;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * @author Manuel Wrage (IVIanuu)
 */
@AutoService(Processor.class)
public class DustyProcessor extends AbstractProcessor {

    private static final String SUPPORT_FRAGMENT_TYPE = "android.support.v4.app.Fragment";

    private Filer filer;
    private Types typeUtil;
    private Elements elementUtil;

    @Override
    public synchronized void init(ProcessingEnvironment environment) {
        super.init(environment);

        filer = environment.getFiler();
        typeUtil = environment.getTypeUtils();
        elementUtil = environment.getElementUtils();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (Class<? extends Annotation> annotation : getSupportedAnnotations()) {
            types.add(annotation.getCanonicalName());
        }

        return types;
    }

    private Set<Class<? extends Annotation>> getSupportedAnnotations() {
        Set<Class<? extends Annotation>> annotations = new LinkedHashSet<>();

        annotations.add(Clear.class);

        return annotations;
    }

    @Override
    public boolean process(Set<? extends TypeElement> elements, RoundEnvironment environment) {
        Map<TypeElement, ClearingSet> clearMap = findAndParseTargets(environment);

        for (Map.Entry<TypeElement, ClearingSet> entry : clearMap.entrySet()) {
            TypeElement typeElement = entry.getKey();
            ClearingSet clearSet = entry.getValue();

            JavaFile javaFile = clearSet.brewJava();
            try {
                javaFile.writeTo(filer);
            } catch (IOException e) {
                error(typeElement, "Unable to write binding for type %s: %s", typeElement, e.getMessage());
            }
        }

        return false;
    }

    private Map<TypeElement, ClearingSet> findAndParseTargets(RoundEnvironment environment) {
        Map<TypeElement, ClearingSet.Builder> builderMap = new LinkedHashMap<>();
        Set<TypeElement> erasedTargetNames = new LinkedHashSet<>();

        // Process each @AutoClear element.
        for (Element element : environment.getElementsAnnotatedWith(Clear.class)) {
            // we don't SuperficialValidation.validateElement(element)
            // so that an unresolved View type can be generated by later processing rounds
            try {
                parseAutoClear(element, builderMap, erasedTargetNames);
            } catch (Exception e) {
                logParsingError(element, Clear.class, e);
            }
        }


        // Associate superclass binders with their subclass binders. This is a queue-based tree walk
        // which starts at the roots (superclasses) and walks to the leafs (subclasses).
        Deque<Map.Entry<TypeElement, ClearingSet.Builder>> entries =
                new ArrayDeque<>(builderMap.entrySet());
        Map<TypeElement, ClearingSet> clearMap = new LinkedHashMap<>();
        while (!entries.isEmpty()) {
            Map.Entry<TypeElement, ClearingSet.Builder> entry = entries.removeFirst();

            TypeElement type = entry.getKey();
            ClearingSet.Builder builder = entry.getValue();

            TypeElement parentType = findParentType(type, erasedTargetNames);
            if (parentType == null) {
                clearMap.put(type, builder.build());
            } else {
                ClearingSet parentBinding = clearMap.get(parentType);
                if (parentBinding != null) {
                    builder.setParent(parentBinding);
                    clearMap.put(type, builder.build());
                } else {
                    // Has a superclass binding but we haven't built it yet. Re-enqueue for later.
                    entries.addLast(entry);
                }
            }
        }

        return clearMap;
    }

    private void parseAutoClear(Element element, Map<TypeElement, ClearingSet.Builder> builderMap,
                                Set<TypeElement> erasedTargetNames) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Start by verifying common generated code restrictions.
        boolean hasError = isInaccessibleViaGeneratedCode(Clear.class, "fields", element)
                || isInWrongPackage(Clear.class, element)
                || !isSupportFragment(enclosingElement.asType());

        Name simpleName = element.getSimpleName();

        if (hasError) {
            error(enclosingElement, "annotated element must be in a support fragment and cannot be a interface or primitive");
            return;
        }

        // Assemble information on the field.
        ClearingSet.Builder builder = builderMap.get(enclosingElement);
        if (builder == null) {
            builder = getOrCreateBindingBuilder(builderMap, enclosingElement);
        }

        String name = simpleName.toString();

        builder.addFieldClearing(new FieldClearing(name));

        // Add the type-erased version to the valid binding targets set.
        erasedTargetNames.add(enclosingElement);
    }

    private ClearingSet.Builder getOrCreateBindingBuilder(
            Map<TypeElement, ClearingSet.Builder> builderMap, TypeElement enclosingElement) {
        ClearingSet.Builder builder = builderMap.get(enclosingElement);
        if (builder == null) {
            builder = ClearingSet.newBuilder(enclosingElement);
            builderMap.put(enclosingElement, builder);
        }
        return builder;
    }

    /** Finds the parent binder type in the supplied set, if any. */
    private TypeElement findParentType(TypeElement typeElement, Set<TypeElement> parents) {
        TypeMirror type;
        while (true) {
            type = typeElement.getSuperclass();
            if (type.getKind() == TypeKind.NONE) {
                return null;
            }
            typeElement = (TypeElement) ((DeclaredType) type).asElement();
            if (parents.contains(typeElement)) {
                return typeElement;
            }
        }
    }

    private void logParsingError(Element element, Class<? extends Annotation> annotation,
                                 Exception e) {
        StringWriter stackTrace = new StringWriter();
        e.printStackTrace(new PrintWriter(stackTrace));
        error(element, "Unable to parse @%s binding.\n\n%s", annotation.getSimpleName(), stackTrace);
    }

    private boolean isInaccessibleViaGeneratedCode(Class<? extends Annotation> annotationClass,
                                                   String targetThing, Element element) {
        boolean hasError = false;
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Verify method modifiers.
        Set<Modifier> modifiers = element.getModifiers();
        if (modifiers.contains(PRIVATE) || modifiers.contains(STATIC)) {
            error(element, "@%s %s must not be private or static. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // Verify containing type.
        if (enclosingElement.getKind() != CLASS) {
            error(enclosingElement, "@%s %s may only be contained in classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        // Verify containing class visibility is not private.
        if (enclosingElement.getModifiers().contains(PRIVATE)) {
            error(enclosingElement, "@%s %s may not be contained in private classes. (%s.%s)",
                    annotationClass.getSimpleName(), targetThing, enclosingElement.getQualifiedName(),
                    element.getSimpleName());
            hasError = true;
        }

        return hasError;
    }

    private boolean isInWrongPackage(Class<? extends Annotation> annotationClass,
                                     Element element) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
        String qualifiedName = enclosingElement.getQualifiedName().toString();

        if (qualifiedName.startsWith("android.")) {
            error(element, "@%s-annotated class incorrectly in Android framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }
        if (qualifiedName.startsWith("java.")) {
            error(element, "@%s-annotated class incorrectly in Java framework package. (%s)",
                    annotationClass.getSimpleName(), qualifiedName);
            return true;
        }

        return false;
    }

    private boolean isSerializable(TypeMirror typeMirror) {
        TypeMirror serializable = elementUtil.getTypeElement("java.io.Serializable").asType();
        return typeUtil.isAssignable(typeMirror, serializable);
    }

    private boolean isInterface(TypeMirror typeMirror) {
        return typeMirror instanceof DeclaredType
                && ((DeclaredType) typeMirror).asElement().getKind() == INTERFACE;
    }

    private boolean isSupportFragment(TypeMirror typeMirror) {
        return isSubtypeOfType(typeMirror, SUPPORT_FRAGMENT_TYPE);
    }

    private static boolean isSubtypeOfType(TypeMirror typeMirror, String otherType) {
        if (isTypeEqual(typeMirror, otherType)) {
            return true;
        }
        if (typeMirror.getKind() != TypeKind.DECLARED) {
            return false;
        }
        DeclaredType declaredType = (DeclaredType) typeMirror;
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() > 0) {
            StringBuilder typeString = new StringBuilder(declaredType.asElement().toString());
            typeString.append('<');
            for (int i = 0; i < typeArguments.size(); i++) {
                if (i > 0) {
                    typeString.append(',');
                }
                typeString.append('?');
            }
            typeString.append('>');
            if (typeString.toString().equals(otherType)) {
                return true;
            }
        }
        Element element = declaredType.asElement();
        if (!(element instanceof TypeElement)) {
            return false;
        }
        TypeElement typeElement = (TypeElement) element;
        TypeMirror superType = typeElement.getSuperclass();
        if (isSubtypeOfType(superType, otherType)) {
            return true;
        }
        for (TypeMirror interfaceType : typeElement.getInterfaces()) {
            if (isSubtypeOfType(interfaceType, otherType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTypeEqual(TypeMirror typeMirror, String otherType) {
        return otherType.equals(typeMirror.toString());
    }

    private void error(Element element, String message, Object... args) {
        printMessage(Diagnostic.Kind.ERROR, element, message, args);
    }

    private void note(Element element, String message, Object... args) {
        printMessage(Diagnostic.Kind.NOTE, element, message, args);
    }

    private void printMessage(Diagnostic.Kind kind, Element element, String message, Object[] args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }

        processingEnv.getMessager().printMessage(kind, message, element);
    }

}
