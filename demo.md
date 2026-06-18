flowchart TD
    %% Styling Definitions
    classDef user fill:#e1bee7,stroke:#8e24aa,stroke-width:2px,color:#000
    classDef agent fill:#ffcc80,stroke:#f57c00,stroke-width:2px,color:#000
    classDef gateway fill:#90caf9,stroke:#1e88e5,stroke-width:2px,color:#000
    classDef backend fill:#a5d6a7,stroke:#43a047,stroke-width:2px,color:#000
    classDef infra fill:#cfd8dc,stroke:#546e7a,stroke-width:2px,color:#000
    classDef broker fill:#ffab91,stroke:#d84315,stroke-width:2px,color:#000

    User(("User")):::user

    subgraph Orchestration [Macro-Level: AI Agentic Orchestrator]
        Agent["Python AI Orchestrator<br/>(LangGraph / ReAct Loop)"]:::agent
    end

    subgraph Control_Plane [Control Plane: Schema-Aware Service Mesh]
        Gateway["Spring Cloud Gateway<br/>(Dynamic Router & Proxy)"]:::gateway
        Discovery["Discovery Service<br/>(Custom Registry)"]:::infra
        K8s[("Kubernetes API")]:::infra
        Redis[("Redis<br/>(Leader Election)")]:::infra
    end

    subgraph Data_Plane [Micro-Level: Choreographed Domain]
        ServiceA["Service A<br/>(Order Domain)"]:::backend
        ServiceB["Service B<br/>(Inventory Domain)"]:::backend
    end

    subgraph Event_Bus [Kafka Event Bus]
        KafkaInfra[("Infrastructure Topic<br/>(Route Updates)")]:::broker
        KafkaBiz[("Business Topic<br/>(Domain Sagas)")]:::broker
    end

    %% --- Workflow 1: The AI Introspection Loop ---
    User -- "1. Natural Language Request" --> Agent
    Agent -- "2. Introspect Schema /services" --> Gateway
    Gateway -. "Proxies Schema Check" .-> Discovery

    %% --- Workflow 2: Decentralized Discovery Loop ---
    K8s -- "Watches Pods" --> Discovery
    Discovery -- "Maintains Consensus" --> Redis
    Discovery -- "Publishes SERVICE_REGISTERED" --> KafkaInfra
    KafkaInfra -- "Updates Routing Table" --> Gateway

    %% --- Workflow 3: Execution & Choreography ---
    Agent -- "3. Executes API Payload" --> Gateway
    Gateway -- "Routes REST Call" --> ServiceA
    
    ServiceA -- "4. Publishes OrderCreatedEvent" --> KafkaBiz
    KafkaBiz -- "5. Consumes & Acts Independently" --> ServiceB
    ServiceB -- "6. Publishes StockReservedEvent" --> KafkaBiz
    KafkaBiz -. "Completes Saga" .-> ServiceA
