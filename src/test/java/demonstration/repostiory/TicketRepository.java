package demonstration.repostiory;

import demonstration.model.Ticket;
import com.rocketbunny.swiftmapper.repository.Repository;

public interface TicketRepository extends Repository<Ticket, String> {
}
