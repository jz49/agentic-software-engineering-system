package com.example.urlshortener.invariant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.data.jpa.repository.Query;

import com.example.urlshortener.link.LinkRepository;
import com.example.urlshortener.link.LinkService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * The write-path invariant (design.md §3, §11, §12): {@link LinkService} is the only production
 * class that writes through {@link LinkRepository}, and {@code LinkRepository} carries exactly one
 * {@code @Query}, the native {@code nextId()}.
 *
 * <p>Runs on ArchUnit's bytecode import of the production classes; no Spring context, no database.
 * Test classes are excluded on purpose: the invariant is about what ships.
 */
class WritePathInvariantIT {

    static final String APP_PACKAGE = "com.example.urlshortener";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages(APP_PACKAGE);
    }

    // ---------------------------------------------------------------------------------------
    // Rule 1: nobody but LinkService calls LinkRepository.save
    // ---------------------------------------------------------------------------------------

    /**
     * An access -- a call, or a method reference such as {@code repository::save} -- to a
     * {@code save*} method ({@code save}, {@code saveAll}, {@code saveAndFlush},
     * {@code saveAllAndFlush}) whose owner is {@code LinkRepository} or any supertype of it.
     *
     * <p>The supertype clause matters: through a reference typed {@code JpaRepository} or
     * {@code CrudRepository}, the bytecode call is owned by that interface rather than by
     * {@code LinkRepository}, and an owner-equals check would miss it.
     */
    static final DescribedPredicate<JavaAccess<?>> WRITE_THROUGH_LINK_REPOSITORY =
            DescribedPredicate.describe(
                    "a save* method on " + LinkRepository.class.getSimpleName() + " or a supertype of it",
                    WritePathInvariantIT::isWriteThroughLinkRepository);

    private static boolean isWriteThroughLinkRepository(JavaAccess<?> access) {
        AccessTarget target = access.getTarget();
        boolean methodAccess = target instanceof AccessTarget.MethodCallTarget
                || target instanceof AccessTarget.MethodReferenceTarget;
        if (!methodAccess || !target.getName().startsWith("save")) {
            return false;
        }
        String owner = target.getOwner().getName();
        if (owner.equals(LinkRepository.class.getName())) {
            return true;
        }
        if (owner.equals(Object.class.getName())) {
            return false;
        }
        try {
            return Class.forName(owner, false, WritePathInvariantIT.class.getClassLoader())
                    .isAssignableFrom(LinkRepository.class);
        } catch (ClassNotFoundException notOnClasspath) {
            return false;
        }
    }

    private static final ArchCondition<JavaClass> WRITE_THROUGH_LINK_REPOSITORY_CONDITION =
            new ArchCondition<>("access " + WRITE_THROUGH_LINK_REPOSITORY.getDescription()) {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                        if (WRITE_THROUGH_LINK_REPOSITORY.test(access)) {
                            events.add(SimpleConditionEvent.satisfied(access, access.getDescription()));
                        }
                    }
                }
            };

    /**
     * Rule 1. Package-private and static so that a throwaway harness can check this exact rule
     * object against an imported violator.
     */
    static final ArchRule ONLY_LINK_SERVICE_WRITES_LINKS = noClasses()
            .that().doNotHaveFullyQualifiedName(LinkService.class.getName())
            .should(WRITE_THROUGH_LINK_REPOSITORY_CONDITION)
            .because("the append-only, limiter-shaped write path rests on LinkService being the"
                    + " one and only caller of LinkRepository.save (design.md §12)");

    @Test
    void onlyLinkServiceCallsLinkRepositorySave() {
        ONLY_LINK_SERVICE_WRITES_LINKS.check(productionClasses);
    }

    /**
     * Guards Rule 1 against passing vacuously: if the predicate stopped recognising the real
     * write (a rename, a signature change, an ArchUnit upgrade changing access modelling), Rule 1
     * would go green for every codebase. The one compliant write must be seen.
     */
    @Test
    void ruleOnePredicateSeesTheOneRealWriteInLinkService() {
        List<String> writers = productionClasses.stream()
                .flatMap(javaClass -> javaClass.getAccessesFromSelf().stream())
                .filter(WRITE_THROUGH_LINK_REPOSITORY)
                .map(access -> access.getOriginOwner().getName() + "#" + access.getOrigin().getName()
                        + " -> " + access.getTarget().getName())
                .toList();

        assertThat(writers)
                .as("every production access to LinkRepository.save*")
                .containsExactly(LinkService.class.getName() + "#shorten -> save");
    }

    // ---------------------------------------------------------------------------------------
    // Rule 2: LinkRepository declares exactly one @Query, and nativeQuery=true only on nextId()
    // ---------------------------------------------------------------------------------------

    /**
     * Counted by meta-annotation, so Spring Data's {@code @NativeQuery} (itself meta-annotated
     * {@code @Query(nativeQuery = true)}) counts as a {@code @Query} and cannot be used to add a
     * second statement unnoticed.
     */
    @Test
    void linkRepositoryDeclaresExactlyOneQueryAndItIsTheNativeNextId() throws NoSuchMethodException {
        JavaClass repository = productionClasses.get(LinkRepository.class);

        List<JavaMethod> queryMethods = repository.getMethods().stream()
                .filter(method -> method.isMetaAnnotatedWith(Query.class))
                .toList();

        assertThat(queryMethods)
                .as("methods declared on LinkRepository carrying @Query (directly or via a meta-annotation)")
                .extracting(JavaMethod::getFullName)
                .containsExactly(LinkRepository.class.getName() + ".nextId()");

        Method nextId = LinkRepository.class.getDeclaredMethod("nextId");
        MergedAnnotation<Query> query = MergedAnnotations.from(nextId).get(Query.class);
        assertThat(query.isPresent()).isTrue();
        assertThat(query.getBoolean("nativeQuery"))
                .as("nextId() is the one native statement (design.md §11)")
                .isTrue();
        assertThat(nextId.getParameterCount())
                .as("nextId() takes no parameters, so the native statement has no injection surface")
                .isZero();
    }

    /**
     * The other half of "nativeQuery=true only on nextId()": no other method anywhere in the
     * production code carries a native {@code @Query} either, including on a repository that
     * does not exist yet.
     */
    @Test
    void noNativeQueryAnywhereInProductionCodeExceptNextId() {
        List<String> nativeQueryMethods = productionClasses.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isMetaAnnotatedWith(Query.class))
                .filter(method -> MergedAnnotations.from(method.reflect()).get(Query.class).getBoolean("nativeQuery"))
                .map(JavaMethod::getFullName)
                .toList();

        assertThat(nativeQueryMethods)
                .containsExactly(LinkRepository.class.getName() + ".nextId()");
    }
}
