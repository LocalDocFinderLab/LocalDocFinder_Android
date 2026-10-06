package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object SampleDocumentGenerator {

    data class SampleFileItem(
        val fileName: String,
        val content: String,
        val isImage: Boolean = false,
        val isPdf: Boolean = false,
        val cameraModel: String? = null,
        val description: String? = null
    )

    fun seed100TestDocuments(context: Context): Pair<File, List<File>> {
        val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
        val createdFiles = generate100SampleFiles(targetDir)
        return Pair(targetDir, createdFiles)
    }

    fun generate100SampleFiles(targetDir: File): List<File> {
        targetDir.mkdirs()
        val createdFiles = mutableListOf<File>()
        val items = getSampleDefinitions()

        val now = System.currentTimeMillis()
        for ((idx, item) in items.withIndex()) {
            val file = File(targetDir, item.fileName)
            when {
                item.isImage -> createSampleImageFile(file, item)
                item.isPdf -> createSamplePdfFile(file, item)
                else -> {
                    FileOutputStream(file).use { out ->
                        out.write(item.content.toByteArray(Charsets.UTF_8))
                    }
                }
            }
            // Spread document creation/modified timestamps across today, past 7d, 30d, and 90d
            val daysAgo = when (idx % 5) {
                0 -> 0L // today
                1 -> 2L // past 7 days
                2 -> 5L // past 7 days
                3 -> 18L // past 30 days
                else -> 45L // past 90 days
            }
            file.setLastModified(now - (daysAgo * 86_400_000L) - (idx * 3_600_000L))
            createdFiles.add(file)
        }

        return createdFiles
    }

    private fun createSamplePdfFile(file: File, item: SampleFileItem) {
        val document = android.graphics.pdf.PdfDocument()
        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(15, 23, 42)
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.rgb(51, 65, 85)
            textSize = 12f
            isAntiAlias = true
        }

        val title = item.fileName.substringBeforeLast('.').replace('_', ' ')
        canvas.drawText(title, 40f, 60f, titlePaint)

        val linePaint = Paint().apply {
            color = Color.rgb(203, 213, 225)
            strokeWidth = 1f
        }
        canvas.drawLine(40f, 75f, 555f, 75f, linePaint)

        var y = 105f
        val words = item.content.split(" ")
        var currentLine = StringBuilder()
        for (word in words) {
            if (currentLine.length + word.length + 1 > 70) {
                canvas.drawText(currentLine.toString(), 40f, y, textPaint)
                y += 18f
                currentLine = StringBuilder(word)
            } else {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            canvas.drawText(currentLine.toString(), 40f, y, textPaint)
        }

        document.finishPage(page)
        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()
    }

    private fun createSampleImageFile(file: File, item: SampleFileItem) {
        val width = 400
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            color = Color.rgb((50..200).random(), (50..200).random(), (150..255).random())
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        paint.color = Color.WHITE
        paint.textSize = 20f
        canvas.drawText(item.fileName.substringBeforeLast('.'), 20f, 150f, paint)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, "Sony")
            exif.setAttribute(ExifInterface.TAG_MODEL, item.cameraModel ?: "Alpha 7R V")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, item.description ?: "Nature and technical capture")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, item.content)
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:08:15 14:32:00")

            if (item.fileName.contains("Colorado", ignoreCase = true) || item.content.contains("Colorado", ignoreCase = true)) {
                // Set Colorado GPS coordinates (Denver / Rocky Mountain region: Lat 39.7392 N, Lon -104.9903 W)
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,44/1,21/1")
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "104/1,59/1,25/1")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            }

            exif.saveAttributes()
        } catch (_: Exception) {}
    }

    fun getSampleDefinitions(): List<SampleFileItem> {
        val list = mutableListOf<SampleFileItem>()

        // 1. AI & Machine Learning (15 files)
        val aiTopics = listOf(
            Pair("Transformer_Self_Attention_Mechanisms.md", "Scaled dot-product attention softmax(QK^T / sqrt(d_k))V maps contextual query tokens across key-value representation spaces."),
            Pair("LiteRT_Qualcomm_QNN_NPU_Acceleration.pdf", "Qualcomm AI Engine Direct Delegate executes 8-bit quantized integer tensors directly on Hexagon Tensor Processor (HTP)."),
            Pair("BGE_Small_and_MiniLM_Embedding_Models.txt", "384-dimensional dense sentence transformers produce continuous semantic manifolds with unit L2 Euclidean normalization."),
            Pair("Knowledge_Distillation_Teacher_Student_Loss.md", "Kullback-Leibler divergence transfers softened class probabilities from deep teacher networks to compact edge models."),
            Pair("Quantization_Aware_Training_QAT_INT8.txt", "Fake quantization operators model rounding and clipping noise during backward backpropagation passes."),
            Pair("FlashAttention_Memory_SRAM_IO_Tiling.pdf", "Fast exact attention algorithm reduces High Bandwidth Memory accesses through fused GPU SRAM kernel tiling."),
            Pair("Low_Rank_Adaptation_LoRA_Fine_Tuning.json", "{\"method\": \"LoRA\", \"rank\": 16, \"formula\": \"W + B*A\", \"target\": \"query_key_projection\"}"),
            Pair("Mixture_of_Experts_MoE_Sparse_Gating.txt", "Top-k token gating routes input tokens dynamically across specialized feedforward expert modules."),
            Pair("Vector_Database_HNSW_Graph_Index.md", "Hierarchical Navigable Small World graphs achieve logarithmic time complexity for nearest neighbor search."),
            Pair("Reciprocal_Rank_Fusion_Hybrid_Information_Retrieval.txt", "RRF harmonizes BM25 lexical term scores with cosine dense semantic vector distance rankings."),
            Pair("Reinforcement_Learning_PPO_Policy_Gradients.md", "Proximal Policy Optimization clips surrogate objective function preventing destructive parameter updates."),
            Pair("Vision_Transformers_ViT_Patch_Embeddings.txt", "Linear projection divides 2D image grids into sequential 16x16 patch tokens processed by standard encoders."),
            Pair("Diffusion_Models_Score_Matching_DDPM.md", "Forward Markov chain adds Gaussian noise; reverse neural network iteratively reconstructs pristine data distribution."),
            Pair("Rotary_Position_Embeddings_RoPE.txt", "Multiplies complex-valued representation representations by 2D orthogonal rotation matrices preserving relative offsets."),
            Pair("Context_Length_Extrapolation_YaRN.json", "{\"technique\": \"YaRN\", \"scaling\": \"NTK-aware\", \"context_extension\": \"128k_tokens\"}")
        )
        aiTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 2. Systems, Linux Kernel & Hardware (15 files)
        val sysTopics = listOf(
            Pair("Linux_eBPF_In_Kernel_Virtual_Machine.pdf", "Extended Berkeley Packet Filter runs sandboxed bytecode safely inside Linux kernel space without kernel modules."),
            Pair("ARM64_NEON_SIMD_Vector_Instructions.pdf", "128-bit vector registers compute quadruple 32-bit floating point dot-products in single clock execution cycles."),
            Pair("Virtual_Memory_Multi_Level_Page_Tables.txt", "MMU translates 48-bit virtual memory addresses to physical RAM frames through hierarchical PML4 page tables."),
            Pair("Epoll_Edge_Triggered_IO_Multiplexing.md", "Linux epoll monitors file descriptors with O(1) event readiness notification replacing O(N) poll scans."),
            Pair("Linux_Cgroups_v2_Resource_Isolation.json", "{\"subsystem\": \"cgroups_v2\", \"controllers\": [\"cpu\", \"memory\", \"io\"], \"oom_policy\": \"kill_group\"}"),
            Pair("Cache_Coherence_MESI_Protocol.txt", "Modified, Exclusive, Shared, and Invalid bus snooping protocol coordinates L1 and L2 multi-core CPU caches."),
            Pair("Non_Blocking_Lock_Free_Queues_CAS.md", "Atomic compare-and-swap (CAS) primitives maintain concurrent ABA-free linked queues without mutex locks."),
            Pair("Android_Storage_Access_Framework_Internals.txt", "DocumentProvider queries documents across content URIs preserving persistable security permissions."),
            Pair("NVMe_Direct_Memory_Access_DMA.pdf", "Asynchronous submission and completion rings bypass CPU interrupts for gigabyte-per-second storage reads."),
            Pair("Linux_SELinux_Mandatory_Access_Control.txt", "Type enforcement security policies govern process capabilities and inode file descriptor access rules."),
            Pair("Thread_Affinity_and_NUMA_Architecture.json", "{\"numa_nodes\": 2, \"affinity_mask\": \"0x000000FF\", \"memory_policy\": \"bind_local\"}"),
            Pair("WorkManager_Foreground_DataSync_Services.txt", "Android WorkManager issues ongoing notifications preventing process termination during intense file indexing."),
            Pair("SQLite_Write_Ahead_Logging_WAL_Concurrency.md", "WAL mode allows concurrent readers to query snapshots while single writer appends log frames."),
            Pair("Memory_Mapped_Files_mmap_Zero_Copy.txt", "mmap maps file blocks directly into process virtual memory space avoiding kernel-to-user buffer copying."),
            Pair("CPU_Branch_Prediction_Two_Level_Adaptive.md", "Branch target buffers and global pattern history tables speculative evaluate conditional jumps.")
        )
        sysTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 3. Distributed Systems & Databases (12 files)
        val distTopics = listOf(
            Pair("Raft_Consensus_Leader_Election_Heartbeats.pdf", "Randomized election timers resolve candidate splits ensuring single term authority."),
            Pair("Paxos_Distributed_Consensus_Algorithm.txt", "Two-phase commit protocol with propose and accept stages ensures agreement across unreliable networks."),
            Pair("CAP_Theorem_and_PACELC_Framework.txt", "In the presence of partitions, distributed databases trade off consistency for latency and availability."),
            Pair("Consistent_Hashing_Dynamo_Ring.json", "{\"ring_partitions\": 1024, \"virtual_nodes\": 256, \"replication_factor\": 3}"),
            Pair("Vector_Clocks_and_Causality_Tracking.txt", "Monotonically increasing vector timestamps establish happens-before relations across asynchronous nodes."),
            Pair("CRDT_Conflict_Free_Replicated_Data_Types.md", "State-based and operation-based convergent data types guarantee eventual consistency without central locks."),
            Pair("LSM_Tree_Compaction_SSTable_Storage.txt", "Log-Structured Merge trees write to memory MemTable before flushing sorted string tables to disk."),
            Pair("Raft_Log_Compaction_and_Snapshots.md", "Compacted state machine snapshots truncate persistent Raft logs freeing disk space."),
            Pair("Distributed_Deadlock_Detection_Chandy_Lamport.txt", "Global snapshot marker propagation records distributed process state without halting transactions."),
            Pair("Gossip_Protocol_Failure_Detectors.json", "{\"heartbeat_interval\": \"500ms\", \"phi_accrual_threshold\": 8.0, \"protocol\": \"Scuttlebutt\"}"),
            Pair("Two_Phase_Locking_2PL_Serializability.txt", "Growing phase acquires read/write locks; shrinking phase releases locks preventing serializability anomalies."),
            Pair("Spanner_TrueTime_External_Consistency.pdf", "GPS receivers and atomic clocks bound clock uncertainty enabling globally linearizable read transactions.")
        )
        distTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 4. Quantum Computing & Physics (12 files)
        val physicsTopics = listOf(
            Pair("Qubit_Superposition_Bloch_Sphere.md", "Quantum state |psi> = cos(theta/2)|0> + e^(i*phi)*sin(theta/2)|1> resides on the surface of the unit sphere."),
            Pair("Quantum_Entanglement_EPR_Paradox.txt", "Bell state |Phi+> = (|00> + |11>)/sqrt(2) exhibits non-local correlations violating local realism."),
            Pair("Shors_Polynomial_Integer_Factoring.pdf", "Quantum Fourier Transform computes period r of f(x) = a^x mod N on superposition states."),
            Pair("Grovers_Search_Quadratic_Speedup.txt", "Iterative amplitude amplification searches unsorted database of N items in O(sqrt(N)) query steps."),
            Pair("Surface_Code_Quantum_Error_Correction.md", "2D square lattice of data and syndrome qubits detects bit-flip and phase-flip errors via stabilizers."),
            Pair("Quantum_Teleportation_Protocol.txt", "Transmits unknown quantum state using shared entangled pair and two classical bits of communication."),
            Pair("General_Relativity_Einstein_Field_Equations.pdf", "R_uv - 1/2*R*g_uv + Lambda*g_uv = (8*pi*G / c^4) * T_uv describes spacetime curvature."),
            Pair("Standard_Model_Higgs_Mechanism.txt", "Spontaneous electroweak symmetry breaking endows W and Z bosons and fermions with rest mass."),
            Pair("Black_Hole_Hawking_Radiation.md", "Quantum vacuum fluctuations near event horizons emit thermal blackbody radiation with temperature T_H."),
            Pair("Superconductivity_BCS_Cooper_Pairs.txt", "Electron-phonon interactions bind electrons into zero-spin Cooper pairs condensing into macroscopic quantum ground states."),
            Pair("Cosmic_Microwave_Background_Anisotropies.json", "{\"temperature_kelvin\": 2.7255, \"acoustic_peaks\": 3, \"baryon_density\": 0.048}"),
            Pair("Quantum_Key_Distribution_BB84.txt", "Non-orthogonal photon polarization states detect eavesdropping through disturbance of quantum states.")
        )
        physicsTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 5. Biology, Medicine & Genetics (12 files)
        val bioTopics = listOf(
            Pair("CRISPR_Cas9_Targeted_Genome_Editing.pdf", "Single guide RNA directs Cas9 endonuclease to create targeted double-strand breaks in DNA sequences."),
            Pair("mRNA_Vaccine_Lipid_Nanoparticle_Delivery.pdf", "Ionizable lipids encapsulate messenger RNA protecting nucleoside-modified strands from ribonuclease degradation."),
            Pair("DNA_Polymerase_Chain_Reaction_PCR.txt", "Thermal cycling denatures, anneals primers, and enzymatically extends DNA copies exponentially."),
            Pair("CAR_T_Cell_Immunotherapy_Receptors.md", "Genetically engineered chimeric antigen receptors reprogram patient T-cells to eradicate malignant B-cell antigens."),
            Pair("Protein_Folding_AlphaFold_Inference.pdf", "Evoformer neural modules predict 3D atomic coordinates from multiple sequence alignments."),
            Pair("Monoclonal_Antibodies_Neutralization.txt", "Cloned B-cell antibodies specifically bind viral epitopes blocking receptor-mediated cellular entry."),
            Pair("Human_Genome_Project_Telomere_Sequencing.json", "{\"base_pairs\": \"3.05_billion\", \"protein_coding_genes\": 19969, \"consortium\": \"T2T\"}"),
            Pair("Antibiotic_Resistance_Beta_Lactamase.md", "Bacterial enzymes hydrolyze the four-membered beta-lactam ring of penicillin rendering antibiotics ineffective."),
            Pair("Synaptic_Plasticity_Long_Term_Potentiation.txt", "NMDA receptor activation and calcium influx strengthen synaptic transmission efficiency."),
            Pair("Apoptosis_Caspase_Cascade_Pathways.md", "Intrinsic mitochondrial cytochrome c release activates initiator caspase-9 and executioner caspase-3."),
            Pair("Stem_Cell_Induced_Pluripotency_Factors.txt", "Yamanaka factors Oct4, Sox2, Klf4, and c-Myc reprogram differentiated fibroblasts into pluripotent states."),
            Pair("Epigenetics_DNA_Methylation_Chromatin.json", "{\"modification\": \"5-methylcytosine\", \"histone_marks\": [\"H3K4me3\", \"H3K27me3\"], \"silencing\": true}")
        )
        bioTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 6. Finance, High-Frequency Trading & Cryptography (12 files)
        val finTopics = listOf(
            Pair("Black_Scholes_Option_Pricing_Model.pdf", "Analytical formula evaluates European call prices based on asset spot price, strike, volatility, and risk-free interest."),
            Pair("High_Frequency_Trading_Limit_Order_Book.json", "{\"bid_depth\": 50, \"ask_depth\": 50, \"tick_size\": 0.01, \"matching_engine\": \"FIFO\"}"),
            Pair("Zero_Knowledge_Proofs_ZK_SNARKs.pdf", "Groth16 and PLONK systems prove polynomial equation validity without disclosing witness input secrets."),
            Pair("Elliptic_Curve_Cryptography_secp256k1.txt", "Weierstrass equation y^2 = x^3 + 7 over prime field F_p provides 128-bit symmetric security level."),
            Pair("Automated_Market_Makers_Constant_Product.md", "Uniswap x * y = k formula dynamically adjusts marginal exchange rates based on pooled token reserves."),
            Pair("Value_at_Risk_VaR_Monte_Carlo_Simulation.txt", "Simulates 100,000 asset price trajectories calculating maximum expected portfolio loss at 99% confidence."),
            Pair("Blockchain_Merkle_Patricia_Tries.json", "{\"root_hash\": \"0x8f3c...\", \"tree_type\": \"Hexary_Patricia\", \"state_storage\": \"LevelDB\"}"),
            Pair("Bond_Duration_Convexity_Interest_Sensitivities.txt", "Macaulay duration measures weighted average maturity of cash flows predicting interest rate price elasticity."),
            Pair("Arbitrage_Pricing_Theory_Factor_Models.md", "Multi-factor linear regression models asset returns against macroeconomic, inflation, and liquidity risk factors."),
            Pair("Cryptographic_Hash_Functions_SHA256.txt", "Merkle-Damgard construction processes 512-bit message blocks with round constants and bitwise rotations."),
            Pair("Decentralized_Finance_Collateralized_Debt_Positions.json", "{\"vault_id\": 1042, \"collateral_ratio\": 1.65, \"liquidation_threshold\": 1.45}"),
            Pair("Algorithmic_Stablecoins_Seigniorage_Shares.txt", "Dual-token algorithmic mint and burn mechanisms defend monetary peg against speculative supply shifts.")
        )
        finTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 7. Space Exploration & Planetary Science (12 files)
        val spaceTopics = listOf(
            Pair("Ion_Thruster_Hall_Effect_Propulsion.md", "Magnetic fields trap electrons ionizing xenon gas accelerated by electrostatic potentials up to 50 km/s."),
            Pair("James_Webb_Space_Telescope_Spectroscopy.pdf", "NIRSpec and MIRI instruments detect carbon dioxide and water atmospheric signatures on exoplanet atmospheres."),
            Pair("Hohmann_Transfer_Orbits_Delta_V.md", "Two-impulse elliptical trajectory transitions spacecraft between coplanar circular orbits minimizing propellant mass."),
            Pair("Mars_Perseverance_Rover_Sample_Caching.txt", "Drills geological rock cores in Jezero crater sealing hermetic titanium tubes for future Earth return."),
            Pair("Europa_Clipper_Subsurface_Ocean_Radar.json", "{\"instrument\": \"REASON\", \"frequency\": \"9MHz_and_60MHz\", \"penetration\": \"30km_ice\"}"),
            Pair("Gravitational_Wave_Astronomy_LIGO_Interferometry.pdf", "Laser interferometers detect spacetime strain perturbations h ~ 10^-21 from binary neutron star mergers."),
            Pair("Artemis_Orion_Spacecraft_Heat_Shield.txt", "Avcoat ablative heat shield dissipates lunar reentry atmospheric friction temperatures exceeding 2700 degrees Celsius."),
            Pair("Planetary_Defense_DART_Kinetic_Impact.md", "Kinetic impactor spacecraft altered binary asteroid Dimorphos orbital period by 33 minutes proving kinetic deflection."),
            Pair("Solar_Orbiter_Extreme_Ultraviolet_Imager.txt", "High-resolution coronal imagery reveals miniature magnetic reconnection events termed campfire flares."),
            Pair("Relativistic_Rocket_Equation_Time_Dilation.json", "{\"propellant\": \"Antimatter\", \"specific_impulse\": \"30000000_sec\", \"gamma_factor\": 5.2}"),
            Pair("Voyager_1_Interstellar_Plasma_Densities.txt", "PWS instrument measured plasma frequency oscillations confirming passage through the heliopause into interstellar space."),
            Pair("Black_Hole_Shadow_Event_Horizon_Telescope.md", "Global VLBI array synthesized Earth-sized aperture resolving supermassive black hole M87* photon ring.")
        )
        spaceTopics.forEach { list.add(SampleFileItem(it.first, it.second, isPdf = it.first.endsWith(".pdf"))) }

        // 8. Sample Images with rich EXIF metadata
        val imageTopics = listOf(
            SampleFileItem("Colorado_Rocky_Mountains_Aspen.jpg", "Scenic photo of Colorado Rocky Mountains with golden aspen trees and snow-capped peaks in autumn.", isImage = true, cameraModel = "Sony Alpha 7R V", description = "Colorado Rocky Mountains landscape near Aspen and Denver"),
            SampleFileItem("Colorado_Denver_Red_Rocks.jpg", "Red Rocks amphitheater photo in Colorado under bright blue skies.", isImage = true, cameraModel = "Canon EOS R5", description = "Red Rocks Colorado landscape photograph"),
            SampleFileItem("Alpine_Mountain_Sunset.jpg", "Golden hour landscape photo of snowy mountain peaks reflected in alpine lake. Warm orange light.", isImage = true, cameraModel = "Sony Alpha 7R V", description = "Dramatic sunset over Alpine ridge"),
            SampleFileItem("Urban_Architecture_Tokyo_Skyscrapers.jpg", "Futuristic glass architecture and modern skyscrapers at twilight in Shinjuku, Tokyo.", isImage = true, cameraModel = "Canon EOS R5", description = "Modern Tokyo skyscraper architecture"),
            SampleFileItem("Cybersecurity_Data_Center_Server_Racks.jpg", "High performance computing server rack corridors with glowing blue and green status LEDs.", isImage = true, cameraModel = "Nikon Z9", description = "Enterprise data center server infrastructure"),
            SampleFileItem("Macro_Silicon_Microchip_Die.jpg", "Microscopic silicon wafer die showing microarchitecture circuit traces and copper interconnects.", isImage = true, cameraModel = "Sony A1", description = "3nm semiconductor transistor architecture"),
            SampleFileItem("Deep_Space_Nebula_Hubble_Astro.jpg", "Astrophotography emission nebula showing ionized hydrogen clouds and stellar nursery clusters.", isImage = true, cameraModel = "JWST NIRCam", description = "Deep space cosmic emission nebula"),
            SampleFileItem("Coral_Reef_Underwater_Marine_Life.jpg", "Vibrant tropical coral reef with clownfish, sea turtles, and turquoise oceanic water.", isImage = true, cameraModel = "Olympus TG-6", description = "Biodiverse coral reef marine ecosystem"),
            SampleFileItem("Robotic_Automated_Factory_Arm.jpg", "Precision industrial robotic manipulator arm welding automotive chassis on smart assembly line.", isImage = true, cameraModel = "Panasonic S5II", description = "Automated manufacturing robotics"),
            SampleFileItem("Historic_Library_Antique_Books.jpg", "Classical wooden library shelves filled with leather-bound legal manuscripts and antique books.", isImage = true, cameraModel = "Leica M11", description = "Historic archive and scholarly library"),
            SampleFileItem("Aurora_Borealis_Northern_Lights.jpg", "Vibrant green geomagnetic aurora borealis shimmering across dark Arctic night sky above fjords.", isImage = true, cameraModel = "Sony A7S III", description = "Arctic northern lights aurora borealis"),
            SampleFileItem("Electric_Vehicle_Battery_Cell_Pack.jpg", "High voltage lithium-ion cylindrical battery module pack with liquid cooling manifolds.", isImage = true, cameraModel = "Fujifilm X-T5", description = "Modular EV battery pack architecture")
        )
        list.addAll(imageTopics)

        return list
    }
}
