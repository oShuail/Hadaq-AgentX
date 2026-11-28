package com.example.hadaq.Repository;

import com.example.hadaq.Entity.WorkOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkOrderRepository extends JpaRepository<WorkOrder, String> {

    List<WorkOrder> findByInspectionIdOrderByCreatedAtAsc(String inspectionId);

    List<WorkOrder> findByCaptureId(String captureId);
}
