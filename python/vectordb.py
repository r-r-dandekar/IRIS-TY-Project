from chromadb import config, Client, Collection
import numpy as np

class VectorDatabase:
    client : Client
    collection : Collection
    def __init__(self, db_path="chroma_facedb"):
        client_settings = config.Settings(is_persistent=True, persist_directory=db_path)
        self.client = Client(client_settings)
        self.collection = self.client.get_or_create_collection(name="face_embeddings", metadata={"hnsw:space": "cosine"})
    
    def save_embedding(self, embedding: list, associated_string: str):
        """
        Saves a vector embedding along with an associated string to the database.
        :param embedding: A list representing the 4096-dimensional vector.
        :param associated_string: A string representing the metadata associated with the embedding.
        """
        if len(embedding) != 4096:
            raise ValueError("Embedding must be 4096 dimensions.")
        self.collection.add(
            documents=[associated_string],
            embeddings=[embedding],
            ids=[str(np.random.randint(1000000))]  # Random ID for each embedding
        )
    
    def search(self, query_embedding: list, top_k: int = 1):
        """
        Searches the database for embeddings similar to the query embedding.
        :param query_embedding: A list representing the query vector.
        :param top_k: The number of top results to return.
        :return: A list of tuples with (associated_string, similarity score).
        """
        results = self.collection.query(
            query_embeddings=[query_embedding],
            n_results=top_k
        )
        print(results)
        result_pairs = []
        print(f"{results['ids'][0]}\n\n")
        for i, id in enumerate(results['ids'][0]):
            result_pairs.append((results['documents'][0][i], results['distances'][0][i]))
            # print(f"{id} {results['documents'][0][i]}")
        return result_pairs

if __name__ == "__main__":
    # Create a VectorDatabase instance
    vector_db = VectorDatabase()

    # Example: Saving an embedding
    example_embedding = list(np.random.rand(4096))  # Convert numpy array to list
    vector_db.save_embedding(example_embedding, "Example document metadata1")

    example_embedding = list(np.random.rand(4096))  # Convert numpy array to list
    vector_db.save_embedding(example_embedding, "Example document metadata2")

    example_embedding = list(np.random.rand(4096))  # Convert numpy array to list
    vector_db.save_embedding(example_embedding, "Example document metadata3")

    # Example: Searching for an embedding
    query_embedding = list(np.random.rand(4096))  # Convert numpy array to list
    results = vector_db.search(query_embedding, top_k=15)
    print("Search Results:", results)