package pl.szymonprofic.foundlybackend.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import pl.szymonprofic.foundlybackend.models.Report;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID>, JpaSpecificationExecutor<Report> {
    @Override
    @EntityGraph(attributePaths = {"author", "category"})
    Optional<Report> findById(UUID id);

    @Override
    @EntityGraph(attributePaths = {"author", "category"})
    Page<Report> findAll(Specification<Report> specification, Pageable pageable);
}
